package com.example.marineradar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.example.marineradar.radar.RadarTarget
import com.example.marineradar.settings.RadarSettings
import kotlin.math.cos
import kotlin.math.sin

private val TargetGreen = Color(0xFF4CD964)
private val TargetAmber = Color(0xFFFFB300)
private val TargetRed = Color(0xFFFF453A)
private val GuardYellow = Color(0xFFFFD54F)

/**
 * Ritar allt som ligger OVANPÅ ekobilden: avståndsringar, kurslinje,
 * vaktzon, spårade mål med kursvektor och spårhistorik samt markören
 * (EBL/VRM) om användaren pekat i bilden.
 *
 * Koordinatsystemet är detsamma som [com.example.marineradar.radar.PpiRenderer]
 * använder: kvadratisk yta, egen båt i mitten och [rangeMeters] vid
 * ytterkanten. Rotationen (North-up/Head-up) sköts av den container som
 * ritar både bitmappen och det här överlägget, så här räknas allt relativt
 * bilden.
 */
@Composable
fun TargetOverlay(
    targets: List<RadarTarget>,
    rangeMeters: Int,
    headingDegrees: Float,
    settings: RadarSettings,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = minOf(cx, cy) - 6f
        val scale = maxRadius / rangeMeters.toFloat().coerceAtLeast(1f)

        if (settings.showRangeRings) {
            for (i in 1..4) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.14f),
                    radius = maxRadius * i / 4f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f)
                )
            }
        }

        if (settings.showHeadingLine) {
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(cx, cy),
                end = Offset(cx, cy - maxRadius),
                strokeWidth = 2f
            )
        }

        if (settings.guardEnabled) {
            val inner = settings.guardInnerMeters * scale
            val outer = settings.guardOuterMeters * scale
            val sweep = settings.guardWidthDeg.coerceIn(1f, 360f)
            // Compose mäter vinklar från 3-läget medurs; radarbäring 0 är uppåt.
            val startAngle = settings.guardStartDeg - 90f
            drawArc(
                color = GuardYellow.copy(alpha = 0.16f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(cx - outer, cy - outer),
                size = Size(outer * 2, outer * 2)
            )
            // "Hål" i mitten görs genom att rita innerarcen i bakgrundsfärg.
            drawArc(
                color = Color.Black.copy(alpha = 0.16f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(cx - inner, cy - inner),
                size = Size(inner * 2, inner * 2)
            )
            drawArc(
                color = GuardYellow.copy(alpha = 0.7f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(cx - outer, cy - outer),
                size = Size(outer * 2, outer * 2),
                style = Stroke(width = 2f)
            )
            drawArc(
                color = GuardYellow.copy(alpha = 0.7f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(cx - inner, cy - inner),
                size = Size(inner * 2, inner * 2),
                style = Stroke(width = 2f)
            )
        }

        for (t in targets) {
            val px = cx + t.relX * scale
            val py = cy - t.relY * scale
            if (px.isNaN() || py.isNaN()) continue
            val dangerous = t.isDangerous(settings.cpaLimitMeters, settings.tcpaLimitSeconds)
            val color = when {
                dangerous -> TargetRed
                t.courseDegrees != null -> TargetAmber
                else -> TargetGreen
            }

            if (settings.showTrails && t.trail.size > 1) {
                t.trail.forEachIndexed { i, p ->
                    val alpha = 0.10f + 0.45f * (i.toFloat() / t.trail.size)
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = 3f,
                        center = Offset(cx + p[0] * scale, cy - p[1] * scale)
                    )
                }
            }

            drawCircle(color = color, radius = 12f, center = Offset(px, py), style = Stroke(width = 2.5f))

            val course = t.courseDegrees
            val speed = t.speedKnots
            if (course != null && speed != null && speed > 0.2f) {
                val meters = speed / 1.94384f * 60f * settings.vectorMinutes
                val rad = Math.toRadians(course.toDouble())
                val ex = px + (sin(rad) * meters * scale).toFloat()
                val ey = py - (cos(rad) * meters * scale).toFloat()
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
