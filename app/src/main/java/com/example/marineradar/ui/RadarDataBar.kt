package com.example.marineradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.marineradar.radar.RadarTarget

private val AccentGreen = Color(0xFF4CD964)
private val AccentAmber = Color(0xFFFFB300)
private val DangerRed = Color(0xFFFF453A)

/**
 * Kompakt navigationsrad ovanpå radarbilden: kurs (HDG från telefonens
 * kompass), COG/SOG från GPS, aktuell räckvidd och antal spårade mål.
 * DRS4W skickar ingen egen navigationsdata, så allt utom räckvidd och mål
 * kommer från telefonens sensorer.
 */
@Composable
fun RadarDataBar(
    headingDegrees: Float,
    courseOverGround: Float?,
    speedKnots: Float?,
    rangeMeters: Int,
    targetCount: Int,
    dangerCount: Int,
    northUp: Boolean = false,
    alarmActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (alarmActive) {
                DataField("LARM", "!", DangerRed)
            }
            DataField("HDG", "%03.0f°".format(headingDegrees))
            DataField("COG", courseOverGround?.let { "%03.0f°".format(it) } ?: "––")
            DataField("SOG", speedKnots?.let { "%.1f kn".format(it) } ?: "––")
            DataField("RNG", formatRange(rangeMeters))
            DataField("LÄGE", if (northUp) "N-UP" else "H-UP", AccentAmber)
            DataField(
                "MÅL",
                if (dangerCount > 0) "$targetCount (${dangerCount}!)" else "$targetCount",
                if (dangerCount > 0) DangerRed else AccentGreen
            )
        }
    }
}

@Composable
private fun DataField(label: String, value: String, valueColor: Color = Color.White) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

/**
 * Lista över spårade mål med bäring, avstånd, kurs, fart och CPA/TCPA –
 * den information man behöver för att bedöma kollisionsrisk.
 */
@Composable
fun TargetListPanel(targets: List<RadarTarget>, modifier: Modifier = Modifier) {
    if (targets.isEmpty()) return
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("MÅL", fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
            targets.take(5).forEach { t ->
                val color = when {
                    t.isDangerous -> DangerRed
                    t.courseDegrees != null -> AccentAmber
                    else -> AccentGreen
                }
                Text(
                    buildString {
                        append("#%d  %03.0f°  %.2f NM".format(t.id, t.bearingDegrees, t.rangeNm))
                        if (t.courseDegrees != null) {
                            append("  →%03.0f° %.1fkn".format(t.courseDegrees, t.speedKnots ?: 0f))
                        }
                        if (t.tcpaSeconds > 1f && t.tcpaSeconds < 3600f) {
                            append("  CPA %.2fNM/%.0fmin".format(t.cpaMeters / 1852f, t.tcpaSeconds / 60f))
                        }
                    },
                    fontSize = 11.sp,
                    color = color
                )
            }
        }
    }
}

private fun formatRange(meters: Int): String {
    val nm = meters / 1852.0
    return if (nm >= 1.0) "%.1f NM".format(nm) else "%.0f m".format(meters.toDouble())
}
