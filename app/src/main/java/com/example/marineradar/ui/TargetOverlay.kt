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
                    color = Color.White.copy(alpha = 0.20f),
                    radius = maxRadius * i / 4f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f)
                )
            }
            // Avståndstext på varje ring – gör det direkt synligt att
            // inställningen faktiskt slår igenom.
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(170, 255, 255, 255)
                    textSize = 18f
                    isAntiAlias = true
                }
                for (i in 1..4) {
                    val r = maxRadius * i / 4f
                    val nm = rangeMeters * i / 4f / 1852f
                    drawText("%.2f NM".format(nm), cx + 4f, cy - r - 4f, paint)
                }
            }
        }

        if (settings.showHeadingLine) {
            drawLine(
                color = Color.White.copy(alpha = 0.55f),
                start = Offset(cx, cy),
                end = Offset(cx, cy - maxRadius),
                strokeWidth = 2.5f
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

            val course = t.courseDegrees
            val speed = t.speedKnots

            // Fartygssymbol: en spets som pekar åt målets kurs när kursen är
            // känd, annars en neutral ring. Farliga mål fylls i så de syns
            // direkt även i en rorig ekobild.
            if (course != null) {
                val rad = Math.toRadians(course.toDouble())
                val dirX = sin(rad).toFloat()
                val dirY = -cos(rad).toFloat()
                val perpX = -dirY
                val perpY = dirX
                val len = 13f
                val wid = 6.5f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(px + dirX * len, py + dirY * len)
                    lineTo(px - dirX * len * 0.6f + perpX * wid, py - dirY * len * 0.6f + perpY * wid)
                    lineTo(px - dirX * len * 0.35f, py - dirY * len * 0.35f)
                    lineTo(px - dirX * len * 0.6f - perpX * wid, py - dirY * len * 0.6f - perpY * wid)
                    close()
                }
                if (dangerous) {
                    drawPath(path, color = color.copy(alpha = 0.85f))
                } else {
                    drawPath(path, color = color.copy(alpha = 0.30f))
                    drawPath(path, color = color, style = Stroke(width = 2f))
                }
            } else {
                drawCircle(color = color, radius = 9f, center = Offset(px, py), style = Stroke(width = 2.5f))
            }

            if (dangerous) {
                // Extra larmring runt mål som bryter CPA/TCPA-gränsen.
                drawCircle(color = color, radius = 18f, center = Offset(px, py), style = Stroke(width = 1.5f))
            }

            if (course != null && speed != null && speed > 0.2f) {
                val meters = speed / 1.94384f * 60f * settings.vectorMinutes
                val rad = Math.toRadians(course.toDouble())
                val ex = px + (sin(rad) * meters * scale).toFloat()
                val ey = py - (cos(rad) * meters * scale).toFloat()
                drawLine(color = color, start = Offset(px, py), end = Offset(ex, ey), strokeWidth = 2.5f)
                // Pilspets i vektorns ände visar rörelseriktningen tydligt.
                val headLen = 12f
                for (offsetDeg in listOf(150.0, -150.0)) {
                    val a = Math.toRadians(course.toDouble() + offsetDeg)
                    drawLine(
                        color = color,
                        start = Offset(ex, ey),
                        end = Offset(
                            ex + (sin(a) * headLen).toFloat(),
                            ey - (cos(a) * headLen).toFloat()
                        ),
                        strokeWidth = 2.5f
                    )
                }
            }

            if (settings.showTargetLabels) {
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.argb(
                            230,
                            (color.red * 255).toInt(),
                            (color.green * 255).toInt(),
                            (color.blue * 255).toInt()
                        )
                        textSize = 20f
                        isAntiAlias = true
                    }
                    val label = if (speed != null && speed > 0.2f) {
                        "#${t.id} %.1fkn".format(speed)
                    } else {
                        "#${t.id}"
                    }
                    drawText(label, px + 16f, py - 12f, paint)
                }
            }
        }
    }
}
