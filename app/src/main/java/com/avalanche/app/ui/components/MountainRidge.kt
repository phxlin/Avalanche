package com.avalanche.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * A decorative ridge line with snow-capped peaks, used along the bottom edge of the hero card.
 * Purely visual, so it is hidden from accessibility services.
 */
@Composable
fun MountainRidge(rock: Color, snow: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height

        fun p(x: Float, y: Float) = Offset(w * x, h * y)

        // Far range, then near range; each vertex list runs left to right along the ridge.
        val far = listOf(p(0f, 0.70f), p(0.14f, 0.38f), p(0.27f, 0.62f), p(0.43f, 0.22f), p(0.60f, 0.64f), p(0.76f, 0.40f), p(0.90f, 0.66f), p(1f, 0.52f))
        val near = listOf(p(0f, 0.92f), p(0.10f, 0.74f), p(0.22f, 0.90f), p(0.36f, 0.60f), p(0.50f, 0.92f), p(0.68f, 0.66f), p(0.84f, 0.94f), p(1f, 0.78f))

        fun ridge(points: List<Offset>, color: Color) {
            val path = Path().apply {
                moveTo(0f, h)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(w, h)
                close()
            }
            drawPath(path, color)
        }

        /** A jagged snow cap on the peak at [i], reaching a third of the way down each side. */
        fun cap(points: List<Offset>, i: Int, color: Color) {
            val peak = points[i]
            val left = lerp(peak, points[i - 1], 0.34f)
            val right = lerp(peak, points[i + 1], 0.34f)
            val drop = h * 0.10f
            val path = Path().apply {
                moveTo(peak.x, peak.y)
                lineTo(right.x, right.y)
                lineTo(lerp(right, left, 0.25f).x, lerp(right, left, 0.25f).y + drop)
                lineTo(lerp(right, left, 0.5f).x, lerp(right, left, 0.5f).y - drop * 0.2f)
                lineTo(lerp(right, left, 0.75f).x, lerp(right, left, 0.75f).y + drop)
                lineTo(left.x, left.y)
                close()
            }
            drawPath(path, color)
        }

        ridge(far, rock.copy(alpha = 0.16f))
        listOf(1, 3, 5).forEach { cap(far, it, snow.copy(alpha = 0.55f)) }
        ridge(near, rock.copy(alpha = 0.26f))
        listOf(3, 5).forEach { cap(near, it, snow.copy(alpha = 0.8f)) }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
