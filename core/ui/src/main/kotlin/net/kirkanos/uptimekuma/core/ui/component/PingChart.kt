package net.kirkanos.uptimekuma.core.ui.component

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.ui.R
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.core.ui.theme.statusColors
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Min/avg/max response time of a chart series (points without a ping are ignored). */
@Immutable
data class PingStats(
    val minMs: Double,
    val avgMs: Double,
    val maxMs: Double,
) {
    companion object {
        fun of(points: List<ChartPoint>): PingStats? {
            val pings = points.mapNotNull { it.pingMs }
            if (pings.isEmpty()) return null
            return PingStats(minMs = pings.min(), avgMs = pings.average(), maxMs = pings.max())
        }
    }
}

private const val HEADROOM = 1.1
private const val NICE_TWO = 2.0
private const val NICE_FIVE = 5.0
private const val NICE_TEN = 10.0
private const val MIN_AXIS_MS = 10.0
private const val MULTI_DAY_MILLIS = 36 * 60 * 60 * 1000L

/** Rounds [value] up to 1, 2 or 5 times a power of ten (axis maximum). */
fun niceAxisMax(value: Double): Double {
    val target = (value * HEADROOM).coerceAtLeast(MIN_AXIS_MS)
    val magnitude = NICE_TEN.pow(floor(log10(target)))
    val fraction = target / magnitude
    val nice =
        when {
            fraction <= 1.0 -> 1.0
            fraction <= NICE_TWO -> NICE_TWO
            fraction <= NICE_FIVE -> NICE_FIVE
            else -> NICE_TEN
        }
    return ceil(nice * magnitude)
}

@Composable
private fun pingChartDescription(stats: PingStats?): String =
    if (stats == null) {
        stringResource(R.string.core_ui_chart_no_data)
    } else {
        stringResource(
            R.string.core_ui_chart_description,
            stats.minMs.roundToInt(),
            stats.avgMs.roundToInt(),
            stats.maxMs.roundToInt(),
        )
    }

/** Tiny response-time line without axes, for list rows or cards. */
@Composable
fun PingSparkline(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val stats = remember(points) { PingStats.of(points) }
    val downColor = MaterialTheme.statusColors.down
    val description = pingChartDescription(stats)
    Spacer(
        modifier =
            modifier
                .height(height)
                .clearAndSetSemantics { contentDescription = description }
                .drawWithCache {
                    val axisMax = stats?.let { niceAxisMax(it.maxMs) } ?: 1.0
                    val geometry = buildChartGeometry(points, size, axisMax)
                    val strokeWidth = 1.5.dp.toPx()
                    onDrawBehind {
                        drawSeries(geometry, color, downColor, strokeWidth)
                    }
                },
    )
}

/**
 * Response-time chart with a y axis (0 / half / max in ms) and start/middle/end time labels.
 * Intervals with failed checks are shaded in the down color. Drawn with a cached path, so
 * recomposition and redraw stay cheap for the ~1440 points of a 24 h period.
 */
@Composable
fun PingChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val stats = remember(points) { PingStats.of(points) }
    val axisMax = remember(stats) { stats?.let { niceAxisMax(it.maxMs) } ?: 1.0 }
    val downColor = MaterialTheme.statusColors.down
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val textMeasurer = rememberTextMeasurer()
    val pingUnit = stringResource(R.string.core_ui_ping_ms_axis)
    val description = pingChartDescription(stats)
    val locale = currentLocale()

    Column(modifier = modifier.clearAndSetSemantics { contentDescription = description }) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    .drawWithCache {
                        val labels =
                            listOf(axisMax, axisMax / 2, 0.0).map {
                                textMeasurer.measure(String.format(locale, pingUnit, it.roundToInt()), labelStyle)
                            }
                        val labelWidth = labels.maxOf { it.size.width }.toFloat() + 6.dp.toPx()
                        val labelHalfHeight = labels.first().size.height / 2f
                        val plotSize = Size(size.width - labelWidth, size.height - labelHalfHeight * 2)
                        val geometry = buildChartGeometry(points, plotSize, axisMax)
                        val strokeWidth = 2.dp.toPx()
                        onDrawBehind {
                            labels.forEachIndexed { index, label ->
                                val y = labelHalfHeight + plotSize.height * index / 2f
                                drawLine(gridColor, Offset(labelWidth, y), Offset(size.width, y), 1.dp.toPx())
                                drawText(label, topLeft = Offset(0f, y - labelHalfHeight))
                            }
                            translate(left = labelWidth, top = labelHalfHeight) {
                                drawSeries(geometry, color, downColor, strokeWidth)
                            }
                        }
                    },
        )
        if (points.isNotEmpty()) {
            val start = points.first().timeMillis
            val end = points.last().timeMillis
            val pattern =
                remember(locale, end - start) {
                    val skeleton = if (end - start > MULTI_DAY_MILLIS) "EEEjmm" else "jmm"
                    DateFormat.getBestDateTimePattern(locale, skeleton)
                }
            val formatter = remember(pattern, locale) { SimpleDateFormat(pattern, locale) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(start, (start + end) / 2, end).forEach {
                    Text(text = formatter.format(Date(it)), style = labelStyle)
                }
            }
        }
    }
}

private class ChartGeometry(
    val line: Path,
    val fill: Path,
    /** x center and width of intervals with down beats. */
    val downBands: List<Pair<Float, Float>>,
    val height: Float,
)

private fun buildChartGeometry(
    points: List<ChartPoint>,
    size: Size,
    axisMax: Double,
): ChartGeometry {
    val line = Path()
    val fill = Path()
    val bands = ArrayList<Pair<Float, Float>>()
    if (points.isEmpty() || size.width <= 0f || size.height <= 0f) {
        return ChartGeometry(line, fill, bands, size.height)
    }
    val start = points.first().timeMillis
    val span = (points.last().timeMillis - start).coerceAtLeast(1L).toFloat()
    val bandWidth = (size.width / points.size).coerceAtLeast(2f)

    fun xOf(time: Long): Float = if (points.size == 1) size.width / 2f else (time - start) / span * size.width

    var segmentStartX: Float? = null
    var lastX = 0f
    for (point in points) {
        val x = xOf(point.timeMillis)
        if (point.downCount > 0) bands += x to bandWidth
        val ping = point.pingMs
        if (ping == null) {
            segmentStartX?.let { closeFill(fill, lastX, size.height) }
            segmentStartX = null
            continue
        }
        val y = size.height - (ping / axisMax).toFloat().coerceIn(0f, 1f) * size.height
        if (segmentStartX == null) {
            line.moveTo(x, y)
            fill.moveTo(x, size.height)
            fill.lineTo(x, y)
            segmentStartX = x
        } else {
            line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        lastX = x
    }
    segmentStartX?.let { closeFill(fill, lastX, size.height) }
    return ChartGeometry(line, fill, bands, size.height)
}

private fun closeFill(
    fill: Path,
    x: Float,
    bottom: Float,
) {
    fill.lineTo(x, bottom)
    fill.close()
}

private fun DrawScope.drawSeries(
    geometry: ChartGeometry,
    color: Color,
    downColor: Color,
    strokeWidth: Float,
) {
    geometry.downBands.forEach { (x, width) ->
        drawRect(
            color = downColor.copy(alpha = 0.25f),
            topLeft = Offset(x - width / 2f, 0f),
            size = Size(width, geometry.height),
        )
    }
    drawPath(
        path = geometry.fill,
        brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f))),
    )
    drawPath(
        path = geometry.line,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PingChartPreview() {
    val start = 1_761_600_000_000L
    val points =
        List(120) { i ->
            val down = i in 70..73
            ChartPoint(
                timeMillis = start + i * 3 * 60_000L,
                pingMs = if (down) null else 80 + 30 * sin(i / 8.0) + (i % 7) * 4,
                upCount = if (down) 0 else 1,
                downCount = if (down) 1 else 0,
            )
        }
    KumaTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PingSparkline(points = points, modifier = Modifier.fillMaxWidth())
                PingChart(points = points)
                PingChart(points = emptyList(), chartHeight = 80.dp)
            }
        }
    }
}
