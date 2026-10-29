package net.kirkanos.uptimekuma.core.data.cache

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.data.db.KumaDatabase
import net.kirkanos.uptimekuma.core.kumaclient.KumaEvent
import net.kirkanos.uptimekuma.core.kumaclient.KumaEventParser
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * Benchmark-style check of the cache with a large server: 500 monitors x 100 beats (the size of Kuma's
 * login burst), generated from the real 2.5.8 `monitorList` payload. Runs on real Room (bundled SQLite on
 * the JVM). Prints the timings; the asserted bounds are deliberately generous (CI machines vary), they only
 * catch accidental quadratic behaviour. Typical numbers on a laptop are in docs/architecture.md.
 */
class LargeServerPerformanceTest {
    private lateinit var db: KumaDatabase
    private lateinit var cache: RoomKumaCache

    @BeforeEach
    fun openDatabase() {
        db =
            Room
                .inMemoryDatabaseBuilder(mockk<Context>(relaxed = true), KumaDatabase::class.java)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                // JVM: no Looper, so Room cannot tell the main thread apart; the check is moot here.
                .allowMainThreadQueries()
                .build()
        cache = RoomKumaCache(db)
    }

    @AfterEach
    fun closeDatabase() = db.close()

    @Test
    fun `500 monitors x 100 beats - parse, write and build the overview`() =
        runBlocking {
            val monitorListJson = monitorListJson(MONITORS)
            val heartbeatListJson = (1..MONITORS).map { heartbeatListJson(it.toLong(), BEATS) }

            val (events, parseTime) =
                measureTimedValue {
                    val monitorList = parse("monitorList", monitorListJson)
                    listOf(monitorList) + heartbeatListJson.map { parse("heartbeatList", it) }
                }
            val sync = KumaEventSync(cache)
            val writeTime = timed { events.forEach { sync.apply(it, api = null) } }

            val (overview, overviewTime) =
                measureTimedValue {
                    MonitorOverviewSource
                        .overviewFlow(
                            db.monitorDao().observeAll(),
                            db.monitorDao().observeAllTags(),
                            db.heartbeatDao().observeLatest(HeartbeatBuffer.OVERVIEW_BEATS),
                            db.statsDao().observeUptime(),
                            db.statsDao().observeCerts(),
                            db.maintenanceDao().observeIds(),
                        ).first()
                }

            // One live heartbeat per monitor, like the next 20 s of a busy server.
            val liveTime =
                timed {
                    (1..MONITORS).forEach { id ->
                        cache.addHeartbeat(
                            Heartbeat(id.toLong(), MonitorStatus.UP, BASE_TIME + BEATS * STEP + 1, "OK", 42.0),
                        )
                    }
                }

            // Steady state: the ring buffer holds 150 recent beats per monitor; compare both beat queries.
            (1..MONITORS).forEach { id ->
                val more = (1..EXTRA_BEATS).map { i -> Heartbeat(id.toLong(), MonitorStatus.UP, BASE_TIME - i * STEP) }
                cache.putHeartbeats(id.toLong(), more, replace = false)
            }
            // Best of five, alternating, so JIT warm-up does not favour either query.
            val runs =
                (1..5).map {
                    measureTimedValue {
                        db
                            .heartbeatDao()
                            .observeAll()
                            .first()
                            .size
                    } to
                        measureTimedValue {
                            db
                                .heartbeatDao()
                                .observeLatest(
                                    HeartbeatBuffer.OVERVIEW_BEATS,
                                ).first()
                                .size
                        }
                }
            val allRows = runs.first().first.value
            val allTime = runs.minOf { it.first.duration }
            val windowRows = runs.first().second.value
            val windowTime = runs.minOf { it.second.duration }

            println(
                "LargeServerPerformanceTest: parse=$parseTime write=$writeTime overview=$overviewTime " +
                    "live(500 beats)=$liveTime all-beats query=$allRows rows/$allTime " +
                    "windowed query=$windowRows rows/$windowTime",
            )
            assertEquals(MONITORS * HeartbeatBuffer.OVERVIEW_BEATS, windowRows)
            assertEquals(MONITORS, overview.size)
            assertTrue(overview.all { it.recentBeats.size == HeartbeatBuffer.OVERVIEW_BEATS })
            assertTrue(parseTime < 20.seconds, "parse took $parseTime")
            assertTrue(writeTime < 60.seconds, "write took $writeTime")
            assertTrue(overviewTime < 20.seconds, "overview took $overviewTime")
            assertTrue(liveTime < 60.seconds, "live beats took $liveTime")
        }

    @Test
    fun `windowed beat query returns the same overview as reading every beat`() =
        runBlocking {
            val sync = KumaEventSync(cache)
            sync.apply(parse("monitorList", monitorListJson(20)), api = null)
            // Uneven beat counts, more than the overview shows, plus important beats the ring buffer keeps.
            (1..20).forEach { id ->
                sync.apply(parse("heartbeatList", heartbeatListJson(id.toLong(), id * 15, importantEvery = 7)), null)
            }
            val all = db.heartbeatDao().observeAll().first()
            val latest = db.heartbeatDao().observeLatest(HeartbeatBuffer.OVERVIEW_BEATS).first()
            assertTrue(latest.size < all.size, "the window drops old beats (${latest.size} < ${all.size})")
            assertTrue(latest.groupBy { it.monitorId }.values.all { it.size <= HeartbeatBuffer.OVERVIEW_BEATS })

            fun build(beats: List<net.kirkanos.uptimekuma.core.data.db.HeartbeatEntity>) =
                runBlocking {
                    OverviewBuilder.build(
                        db.monitorDao().observeAll().first(),
                        db.monitorDao().observeAllTags().first(),
                        beats,
                        emptyList(),
                        emptyList(),
                        emptySet(),
                    )
                }
            assertEquals(build(all), build(latest))
        }

    private suspend fun timed(block: suspend () -> Unit): Duration = measureTimedValue { block() }.duration

    /** Like KumaClient: each Socket.IO argument arrives as JSON text and is parsed, then mapped to an event. */
    private fun parse(
        event: String,
        argsJson: List<String>,
    ): KumaEvent {
        val args: List<JsonElement> = argsJson.map { KumaJson.json.parseToJsonElement(it) }
        return requireNotNull(KumaEventParser.parse(event, args))
    }

    private fun monitorListJson(count: Int): List<String> {
        val sample =
            KumaJson.json
                .parseToJsonElement(fixture("monitorList.json").readText())
                .jsonObject
                .getValue("samples")
                .jsonArray
                .first()
                .jsonObject
                .getValue("args")
                .jsonArray
                .first()
                .jsonObject
        val template = sample.values.first().jsonObject
        val monitors =
            (1..count).associate { id ->
                id.toString() to
                    JsonObject(
                        template +
                            mapOf(
                                "id" to JsonPrimitive(id),
                                "name" to JsonPrimitive("Monitor $id"),
                                "pathName" to JsonPrimitive("Monitor $id"),
                                "path" to JsonArray(listOf(JsonPrimitive("Monitor $id"))),
                                "weight" to JsonPrimitive(id % 10),
                            ),
                    )
            }
        return listOf(JsonObject(monitors).toString())
    }

    private fun heartbeatListJson(
        monitorId: Long,
        count: Int,
        importantEvery: Int = 50,
    ): List<String> {
        val rows =
            (0 until count).joinToString(",", "[", "]") { i ->
                val time = KUMA_TIME.format(java.time.Instant.ofEpochMilli(BASE_TIME + i * STEP))
                val important = if (i % importantEvery == 0) 1 else 0
                """{"id":${monitorId * 1000 + i},"important":$important,"monitor_id":$monitorId,"status":1,""" +
                    """"msg":"200 - OK","time":"$time","ping":${40 + i % 30},"duration":20,"down_count":0,""" +
                    """"end_time":"$time","retries":0,"response":null}"""
            }
        return listOf(monitorId.toString(), rows, "false")
    }

    private fun fixture(name: String): File {
        val dir =
            listOfNotNull(System.getProperty("kuma.fixtures")?.let(::File), File("../../fixtures/kuma-2.5.8"))
                .first { it.isDirectory }
        return File(dir, name)
    }

    private companion object {
        const val MONITORS = 500
        const val BEATS = 100
        const val EXTRA_BEATS = 50
        const val BASE_TIME = 1_793_000_000_000L
        const val STEP = 20_000L
        val KUMA_TIME: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(java.time.ZoneOffset.UTC)
    }
}
