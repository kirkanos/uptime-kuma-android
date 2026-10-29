package net.kirkanos.uptimekuma.push.alerts

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.kirkanos.uptimekuma.core.domain.AlertHistoryRepository
import net.kirkanos.uptimekuma.core.model.AlertRecord
import net.kirkanos.uptimekuma.push.di.PushScope
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last [MAX_ENTRIES] received alerts as a JSON DataStore file (`alert_history.json`), newest first.
 * Not to be confused with [AlertHistory][net.kirkanos.uptimekuma.push.notify.AlertHistory], which only
 * deduplicates replays.
 */
@Singleton
class AlertHistoryStore internal constructor(
    private val dataStore: DataStore<List<AlertRecord>>,
    private val scope: CoroutineScope,
) : AlertHistoryRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @PushScope scope: CoroutineScope,
    ) : this(create(scope) { context.dataStoreFile(FILE_NAME) }, scope)

    override fun observeAlerts(): Flow<List<AlertRecord>> =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyList()) else throw e }

    /** Appends [record] in the background (called from the push service, which must not suspend). */
    fun record(record: AlertRecord) {
        scope.launch {
            try {
                add(record)
            } catch (e: IOException) {
                Log.w(TAG, "Writing alert history failed", e)
            }
        }
    }

    suspend fun add(record: AlertRecord) {
        dataStore.updateData { current ->
            (listOf(record) + current)
                .sortedByDescending { it.receivedAtMillis }
                .take(MAX_ENTRIES)
        }
    }

    override suspend fun clear() {
        dataStore.updateData { emptyList() }
    }

    internal companion object {
        const val MAX_ENTRIES = 200
        const val FILE_NAME = "alert_history.json"
        private const val TAG = "AlertHistoryStore"

        fun create(
            scope: CoroutineScope,
            produceFile: () -> File,
        ): DataStore<List<AlertRecord>> =
            DataStoreFactory.create(
                serializer = AlertRecordsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
                scope = scope,
                produceFile = produceFile,
            )
    }
}

/** JSON (kotlinx.serialization) file format of the alert history. */
internal object AlertRecordsSerializer : Serializer<List<AlertRecord>> {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    override val defaultValue: List<AlertRecord> = emptyList()

    override suspend fun readFrom(input: InputStream): List<AlertRecord> =
        try {
            json.decodeFromString<StoredAlerts>(input.readBytes().decodeToString()).alerts.map { it.toModel() }
        } catch (e: SerializationException) {
            throw CorruptionException("Alert history is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Alert history has an unexpected shape", e)
        }

    override suspend fun writeTo(
        t: List<AlertRecord>,
        output: OutputStream,
    ) {
        output.write(json.encodeToString(StoredAlerts(t.map(StoredAlert::of))).encodeToByteArray())
    }
}
