package com.example.marineradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.marineradar.network.RadarControls
import kotlin.math.roundToInt

private val AccentGreen = Color(0xFF4CD964)

/**
 * Radarns kontroller (Standby/Transmit, Gain, Sea, Rain). Visas inuti
 * den expanderbara "Kontroller"-panelen (se [ExpandableBottomBar]).
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
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = { onPowerToggle(!controls.powerTransmit) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    if (controls.powerTransmit) "TX" else "STBY",
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

        ControlSliderRow("Gain", controls.gainAuto, controls.gainValue, onGainChange)
        ControlSliderRow("Sea", controls.seaAuto, controls.seaValue, onSeaChange)
        ControlSliderRow("Rain", controls.rainAuto, controls.rainValue, onRainChange)
    }
}

@Composable
private fun ControlSliderRow(
    label: String,
    auto: Boolean,
    value: Int,
    onChange: (Boolean, Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.width(42.dp),
            style = MaterialTheme.typography.labelMedium
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(false, it.roundToInt()) },
            valueRange = 0f..100f,
            enabled = !auto,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            colors = SliderDefaults.colors()
        )
        Text("A", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 2.dp))
        Switch(
            checked = auto,
            onCheckedChange = { onChange(it, value) },
            modifier = Modifier.width(40.dp),
            colors = SwitchDefaults.colors()
        )
    }
}
