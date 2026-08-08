package com.example.marineradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.marineradar.map.MapProviderType
import com.example.marineradar.map.MapStyle
import com.example.marineradar.settings.RadarSettings
import kotlin.math.roundToInt

/**
 * Samlad inställningsvy. ALLT som ändras här sparas direkt och gäller även
 * nästa gång appen startas (se [com.example.marineradar.settings.SettingsStore]).
 *
 * Vyn är medvetet "state hoisted": den äger ingen egen inställning utan
 * skickar bara upp en transform-funktion, så att det bara finns EN sanning
 * (ViewModel + SharedPreferences) och inga lägen kan gå isär.
 */
@Composable
fun SettingsScreen(
    settings: RadarSettings,
    onChange: ((RadarSettings) -> RadarSettings) -> Unit,
    onResetSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var ssid by remember(settings.ssid) { mutableStateOf(settings.ssid) }
    var password by remember(settings.password) { mutableStateOf(settings.password) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionTitle("Anslutning")
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it; onChange { s -> s.copy(ssid = it) } },
            label = { Text("Radarns WiFi-namn (SSID)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; onChange { s -> s.copy(password = it) } },
            label = { Text("Lösenord") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        SettingSwitch(
            "Starta sändning automatiskt",
            "Begär TRANSMIT så fort kommandokanalen är uppe – annars startar radarn i standby.",
            settings.autoTransmitOnConnect
        ) { v -> onChange { it.copy(autoTransmitOnConnect = v) } }

        SectionTitle("Visning")
        SettingSwitch(
            "Norr uppåt",
            if (settings.northUp) "Bilden roteras med kompasskursen (North-up)." else "Fören uppåt (Head-up) – som radarn skickar bilden.",
            settings.northUp
        ) { v -> onChange { it.copy(northUp = v) } }
        SettingSwitch("Avståndsringar", "Ringar var fjärdedels räckvidd med avståndstext.", settings.showRangeRings) { v ->
            onChange { it.copy(showRangeRings = v) }
        }
        SettingSwitch("Kurslinje", "Linje rakt föröver.", settings.showHeadingLine) { v ->
            onChange { it.copy(showHeadingLine = v) }
        }
        SettingSwitch("Navigationsrad", "HDG/COG/SOG/räckvidd överst i bilden.", settings.showDataBar) { v ->
            onChange { it.copy(showDataBar = v) }
        }
        SettingSwitch("Mållista", "Lista med de närmaste spårade målen.", settings.showTargetList) { v ->
            onChange { it.copy(showTargetList = v) }
        }
        SettingSwitch("Håll skärmen tänd", "Skärmen släcks inte medan radarn strömmar.", settings.keepScreenOn) { v ->
            onChange { it.copy(keepScreenOn = v) }
        }

        SectionTitle("Målspårning (MARPA-lite)")
        SettingSwitch(
            "Spåra mål",
            "Härleder kurs, fart och CPA/TCPA ur radarbilden.",
            settings.targetTrackingEnabled
        ) { v -> onChange { it.copy(targetTrackingEnabled = v) } }
        SettingSlider(
            "Känslighet (ekotröskel)",
            "${settings.detectThreshold} / 255 – lägre värde ger fler men osäkrare mål",
            settings.detectThreshold.toFloat(), 60f, 220f
        ) { v -> onChange { it.copy(detectThreshold = v.roundToInt()) } }
        SettingSwitch("Spårhistorik (trails)", "Visar var målen kommit ifrån.", settings.showTrails) { v ->
            onChange { it.copy(showTrails = v) }
        }
        SettingSlider(
            "Kursvektor",
            "${settings.vectorMinutes} min förflyttning",
            settings.vectorMinutes.toFloat(), 1f, 30f
        ) { v -> onChange { it.copy(vectorMinutes = v.roundToInt().coerceAtLeast(1)) } }

        SectionTitle("Larm")
        SettingSwitch("Kollisionslarm (CPA)", "Larmar för mål som passerar för nära.", settings.cpaAlarmEnabled) { v ->
            onChange { it.copy(cpaAlarmEnabled = v) }
        }
        SettingSlider(
            "CPA-gräns",
            "%.2f NM".format(settings.cpaLimitNm),
            settings.cpaLimitNm, 0.05f, 2f
        ) { v -> onChange { it.copy(cpaLimitNm = v) } }
        SettingSlider(
            "TCPA-gräns",
            "%.0f min".format(settings.tcpaLimitMinutes),
            settings.tcpaLimitMinutes, 1f, 30f
        ) { v -> onChange { it.copy(tcpaLimitMinutes = v) } }
        SettingSwitch("Ljudsignal", null, settings.alarmSound) { v -> onChange { it.copy(alarmSound = v) } }
        SettingSwitch("Vibration", null, settings.alarmVibrate) { v -> onChange { it.copy(alarmVibrate = v) } }

        SectionTitle("Vaktzon")
        SettingSwitch(
            "Vaktzon aktiv",
            "Larmar när ett mål kommer in i zonen. Zonen ritas gulmarkerad i bilden.",
            settings.guardEnabled
        ) { v -> onChange { it.copy(guardEnabled = v) } }
        SettingSlider("Inre gräns", "%.2f NM".format(settings.guardInnerNm), settings.guardInnerNm, 0.05f, 3f) { v ->
            onChange { it.copy(guardInnerNm = v, guardOuterNm = maxOf(it.guardOuterNm, v + 0.05f)) }
        }
        SettingSlider("Yttre gräns", "%.2f NM".format(settings.guardOuterNm), settings.guardOuterNm, 0.1f, 6f) { v ->
            onChange { it.copy(guardOuterNm = v, guardInnerNm = minOf(it.guardInnerNm, v - 0.05f).coerceAtLeast(0.01f)) }
        }
        SettingSlider("Startbäring", "%03.0f°".format(settings.guardStartDeg), settings.guardStartDeg, 0f, 359f) { v ->
            onChange { it.copy(guardStartDeg = v) }
        }
        SettingSlider(
            "Sektorbredd",
            if (settings.guardWidthDeg >= 359.5f) "hel ring" else "%.0f°".format(settings.guardWidthDeg),
            settings.guardWidthDeg, 10f, 360f
        ) { v -> onChange { it.copy(guardWidthDeg = v) } }

        SectionTitle("Karta")
        Text(
            "Kartleverantör",
            style = MaterialTheme.typography.bodyMedium
        )
        Column(Modifier.fillMaxWidth()) {
            MapProviderType.values().forEach { provider ->
                OutlinedButton(
                    onClick = { onChange { it.copy(mapProviderName = provider.name) } },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        provider.displayName,
                        fontWeight = if (settings.mapProviderName == provider.name) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Kartfilter", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Kan även bytas direkt i kartbilden via lager-ikonen (\u2630).",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.fillMaxWidth()) {
            MapStyle.values().forEach { style ->
                OutlinedButton(
                    onClick = { onChange { it.copy(mapStyleName = style.name) } },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        "${style.icon}  ${style.displayName}",
                        fontWeight = if (settings.mapStyleName == style.name) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        SettingSlider(
            "Radarns genomskinlighet på kartan",
            "%.0f %%".format(settings.radarOpacity * 100),
            settings.radarOpacity, 0.1f, 1f
        ) { v -> onChange { it.copy(radarOpacity = v) } }

        SectionTitle("Felsökning")
        SettingSwitch(
            "Utförlig loggning",
            "Loggar även paketnivå – mycket data, använd bara vid felsökning.",
            settings.verboseLogging
        ) { v -> onChange { it.copy(verboseLogging = v) } }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onResetSettings) { Text("Återställ alla inställningar") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Divider(Modifier.padding(top = 4.dp, bottom = 4.dp))
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
