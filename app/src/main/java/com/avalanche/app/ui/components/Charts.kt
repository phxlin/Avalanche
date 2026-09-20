package com.avalanche.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

data class ChartPoint(val x: Float, val y: Float)

data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<ChartPoint>,
    val dashed: Boolean = false,
)

private fun niceCeil(v: Float): Float {
    val exp = 10.0.pow(floor(log10(v.toDouble())))
    val f = v / exp
    val nice = when {
        f <= 1 -> 1.0
        f <= 2 -> 2.0
        f <= 2.5 -> 2.5
        f <= 5 -> 5.0
        else -> 10.0
    }
    return (nice * exp).toFloat()
}

/** A small dependency-free line chart. The first series gets a soft gradient fill. */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    xLabel: (Float) -> String,
    yLabel: (Float) -> String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val points = series.flatMap { it.points }
    if (points.isEmpty()) return

    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }.let { if (it <= minX) minX + 1f else it }
    val maxY = niceCeil(max(points.maxOf { it.y }, 1f))

    Canvas(
        modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics { contentDescription = description },
    ) {
        val yTicks = listOf(0f, maxY / 2f, maxY)
        val yLayouts = yTicks.map { measurer.measure(yLabel(it), labelStyle) }
        val left = yLayouts.maxOf { it.size.width } + 8.dp.toPx()
        val right = 14.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        fun px(x: Float) = left + (x - minX) / (maxX - minX) * w
        fun py(y: Float) = top + h - (y / maxY) * h

        yTicks.forEachIndexed { i, t ->
            val y = py(t)
            drawLine(gridColor, Offset(left, y), Offset(size.width - right, y), strokeWidth = 1.dp.toPx())
            val layout = yLayouts[i]
            drawText(layout, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }

        val xTicks = listOf(minX, (minX + maxX) / 2f, maxX)
        xTicks.forEachIndexed { i, t ->
            val layout = measurer.measure(xLabel(t), labelStyle)
            val cx = px(t)
            val x = when (i) {
                0 -> cx
                2 -> cx - layout.size.width
                else -> cx - layout.size.width / 2f
            }
            drawText(layout, topLeft = Offset(x, size.height - bottom + 4.dp.toPx()))
        }

        series.forEachIndexed { index, s ->
            val pts = s.points.sortedBy { it.x }
            if (pts.isEmpty()) return@forEachIndexed
            if (pts.size == 1) {
                drawCircle(s.color, 4.dp.toPx(), Offset(px(pts[0].x), py(pts[0].y)))
                return@forEachIndexed
            }
            val line = Path().apply {
                moveTo(px(pts[0].x), py(pts[0].y))
                for (p in pts.drop(1)) lineTo(px(p.x), py(p.y))
            }
            if (index == 0) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(px(pts.last().x), py(0f))
                    lineTo(px(pts.first().x), py(0f))
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), s.color.copy(alpha = 0f)), startY = top, endY = top + h))
            }
            drawPath(
                line,
                s.color,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    // Round caps would fill most of each gap and make a dashed line look solid, hiding what's under it.
                    cap = if (s.dashed) StrokeCap.Butt else StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())) else null,
                ),
            )
            drawCircle(s.color, 3.5.dp.toPx(), Offset(px(pts.last().x), py(pts.last().y)))
        }
    }
}

@Composable
fun ChartLegend(series: List<ChartSeries>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(s.color, RoundedCornerShape(50)))
                Text(s.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

data class TimelineItem(val name: String, val months: Int?, val dateLabel: String, val color: Color)

/** Horizontal bars showing how many months each debt takes to clear, on a shared scale. */
@Composable
fun PayoffTimeline(items: List<TimelineItem>, modifier: Modifier = Modifier) {
    val longest = max(1, items.maxOfOrNull { it.months ?: 0 } ?: 1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    item.name,
                    modifier = Modifier.width(92.dp),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50)),
                ) {
                    val fraction = ((item.months ?: longest).toFloat() / longest).coerceIn(0.04f, 1f)
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(item.color.copy(alpha = if (item.months == null) 0.35f else 1f), RoundedCornerShape(50)),
                    )
                }
                Text(
                    item.dateLabel,
                    modifier = Modifier.width(72.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
