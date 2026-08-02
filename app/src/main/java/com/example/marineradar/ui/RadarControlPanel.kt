package com.example.marineradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.marineradar.network.RadarControls
import kotlin.math.roundToInt

@Composable
fun RadarControlPanel(
    controls: RadarControls,
    onPowerToggle: (Boolean) -> Unit,
    onRangeStep: (Boolean) -> Unit,
    onGainChange: (Boolean, Int) -> Unit,
    onSeaChange: (Boolean, Int) -> Unit,
    onRainChange: (Boolean, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { onPowerToggle(!controls.powerTransmit) }) {
                Text(if (controls.powerTransmit) "Transmit (tryck för Standby)" else "Standby (tryck för Transmit)")
            }
            Text(
                text = if (controls.connected) "● Kommandokanal ansluten" else "○ Ansluter…",
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Räckvidd: ${formatRange(controls.rangeMeters)}", style = MaterialTheme.typography.bodyMedium)
            Row {
                OutlinedButton(onClick = { onRangeStep(false) }) { Text("－") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onRangeStep(true) }) { Text("＋") }
            }
        }

        Spacer(Modifier.height(4.dp))

        ControlSliderRow(
            label = "Gain",
            auto = controls.gainAuto,
            value = controls.gainValue,
            onChange = onGainChange
        )
        ControlSliderRow(
            label = "Sea clutter",
            auto = controls.seaAuto,
            value = controls.seaValue,
            onChange = onSeaChange
        )
        ControlSliderRow(
            label = "Rain clutter",
            auto = controls.rainAuto,
            value = controls.rainValue,
            onChange = onRainChange
        )
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
        Text(label, modifier = Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(false, it.roundToInt()) },
            valueRange = 0f..100f,
            enabled = !auto,
            modifier = Modifier.weight(1f)
        )
        Text("Auto", style = MaterialTheme.typography.labelSmall)
        Switch(
            checked = auto,
            onCheckedChange = { onChange(it, value) }
        )
    }
}

private fun formatRange(meters: Int): String {
    val nm = meters / 1852.0
    return when {
        nm >= 1.0 -> "%.1f NM".format(nm)
        else -> "%.0f m".format(meters.toDouble())
    }
}
