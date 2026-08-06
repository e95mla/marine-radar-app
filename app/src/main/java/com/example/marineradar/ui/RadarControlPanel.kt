package com.example.marineradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
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
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Rad 1: Power + Range + anslutningsstatus, allt kompakt på en rad
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
                    IconButton(onClick = { onRangeStep(false) }, modifier = Modifier.width(32.dp)) {
                        Text("－", fontSize = 16.sp)
                    }
                    Text(
                        formatRange(controls.rangeMeters),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(onClick = { onRangeStep(true) }, modifier = Modifier.width(32.dp)) {
                        Text("＋", fontSize = 16.sp)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "●",
                        color = if (controls.connected) AccentGreen else MaterialTheme.colorScheme.outline,
                        fontSize = 10.sp
                    )
                    Text(
                        if (controls.connected) " Ansluten" else " Ansluter…",
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

private fun formatRange(meters: Int): String {
    val nm = meters / 1852.0
    return when {
        nm >= 1.0 -> "%.1f NM".format(nm)
        else -> "%.0f m".format(meters.toDouble())
    }
}
