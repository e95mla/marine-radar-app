package com.example.marineradar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.example.marineradar.radar.RadarTarget
import kotlin.math.cos
import kotlin.math.sin

private val TargetGreen = Color(0xFF4CD964)
private val TargetAmber = Color(0xFFFFB300)
private val TargetRed = Color(0xFFFF453A)

/**
 * Ritar spårade mål ovanpå PPI-bilden: en ring per mål plus en vektor som
 * visar kurs och (via längden) fart – 1 minuts förflyttning. Röd ring =
 * mål med liten CPA inom kort tid, dvs. kollisionsrisk.
 *
 * Ritas i samma kvadratiska koordinatsystem som [com.example.marineradar.radar.PpiRenderer],
 * dvs. egen båt i mitten och [rangeMeters] vid ytterkanten.
 */
@Composable
fun TargetOverlay(
    targets: List<RadarTarget>,
    rangeMeters: Int,
    headingDegrees: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = minOf(cx, cy) - 6f
        val scale = maxRadius / rangeMeters.toFloat().coerceAtLeast(1f)

        // Kurslinje (heading line) rakt upp = fören.
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = Offset(cx, cy),
            end = Offset(cx, cy - maxRadius),
            strokeWidth = 2f
        )

        for (t in targets) {
            val px = cx + t.relX * scale
            val py = cy - t.relY * scale
            if (px.isNaN() || py.isNaN()) continue
            val color = when {
                t.isDangerous -> TargetRed
                t.courseDegrees != null -> TargetAmber
                else -> TargetGreen
            }
            drawCircle(color = color, radius = 12f, center = Offset(px, py), style = Stroke(width = 2.5f))

            val course = t.courseDegrees
            val speed = t.speedKnots
            if (course != null && speed != null && speed > 0.2f) {
                // Vektorlängd = 60 sekunders förflyttning.
                val metersPerMinute = speed / 1.94384f * 60f
                val rad = Math.toRadians(course.toDouble())
                val ex = px + (sin(rad) * metersPerMinute * scale).toFloat()
                val ey = py - (cos(rad) * metersPerMinute * scale).toFloat()
                drawLine(color = color, start = Offset(px, py), end = Offset(ex, ey), strokeWidth = 2.5f)
            }

            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.argb(
                        220,
                        (color.red * 255).toInt(),
                        (color.green * 255).toInt(),
                        (color.blue * 255).toInt()
                    )
                    textSize = 22f
                    isAntiAlias = true
                }
                drawText("#${t.id}", px + 15f, py - 12f, paint)
            }
        }
    }
}
