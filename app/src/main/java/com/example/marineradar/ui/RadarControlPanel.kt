package com.example.marineradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.marineradar.network.RadarControls
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val AccentGreen = Color(0xFF4CD964)

/**
 * Radarns kontroller (Standby/Transmit, Gain, Sea, Rain). Visas inuti
 * den expanderbara "Kontroller"-panelen (se [ExpandableBottomBar]).
 *
 * Layouten är medvetet uppbyggd med etikett OVANFÖR reglaget istället för
 * "etikett – reglage – A – switch" på samma rad: den gamla raden klämde
 * ihop texten så att "Auto" hamnade bakom switchen och bara "A" syntes.
 *
 * TX/STBY-knappen visar ett "väntar"-läge tills radarn faktiskt bekräftat
 * det nya effektläget (radarn svarar först efter ca 1 s), så att det inte
 * ser ut som att knappen inte reagerar.
 */
@Composable
fun RadarControlPanel(
    controls: RadarControls,
    onPowerToggle: (Boolean) -> Unit,
    onGainChange: (Boolean, Int) -> Unit,
    onSeaChange: (Boolean, Int) -> Unit,
    onRainChange: (Boolean, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingPower by remember { mutableStateOf<Boolean?>(null) }

    // Släpp väntläget så fort radarn rapporterar det begärda läget – eller
    // efter 6 s, så att knappen aldrig kan fastna i "väntar".
    LaunchedEffect(pendingPower, controls.powerTransmit) {
        val requested = pendingPower ?: return@LaunchedEffect
        if (controls.powerTransmit == requested) {
            pendingPower = null
        } else {
            delay(6_000)
            pendingPower = null
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = {
                    val next = !controls.powerTransmit
                    pendingPower = next
                    onPowerToggle(next)
                },
                enabled = controls.connected,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                if (pendingPower != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        pendingPower == true -> "Startar…"
                        pendingPower == false -> "Stoppar…"
                        controls.powerTransmit -> "TX"
                        else -> "STBY"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "●",
                    color = if (controls.connected) AccentGreen else MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp
                )
                Text(
                    if (controls.connected) " Kommandokanal ansluten" else " Ansluter…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ControlSliderRow("Gain", controls.gainAuto, controls.gainValue, controls.connected, onGainChange)
        ControlSliderRow("Sea", controls.seaAuto, controls.seaValue, controls.connected, onSeaChange)
        ControlSliderRow("Rain", controls.rainAuto, controls.rainValue, controls.connected, onRainChange)
    }
}

@Composable
private fun ControlSliderRow(
    label: String,
    auto: Boolean,
    value: Int,
    enabled: Boolean,
    onChange: (Boolean, Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (auto) "AUTO" else "$value%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Auto", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                Switch(
                    checked = auto,
                    onCheckedChange = { onChange(it, value) },
                    enabled = enabled,
                    colors = SwitchDefaults.colors()
                )
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(false, it.roundToInt()) },
            valueRange = 0f..100f,
            enabled = enabled && !auto,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors()
        )
    }
}
