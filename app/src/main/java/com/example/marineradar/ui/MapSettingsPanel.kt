@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.marineradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.marineradar.map.MapProviderType

/**
 * Kartinställningar (leverantör + hur genomskinlig radarbilden ska vara
 * ovanpå kartan). Visas i den expanderbara "Karta"-panelen.
 */
@Composable
fun MapSettingsPanel(
    provider: MapProviderType,
    onProviderChange: (MapProviderType) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text("Kartleverantör", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            MapProviderType.entries.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = provider == type,
                    onClick = { onProviderChange(type) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = MapProviderType.entries.size)
                ) {
                    Text(type.displayName, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Radarns synlighet",
                modifier = Modifier.width(120.dp),
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = opacity,
                onValueChange = onOpacityChange,
                valueRange = 0.1f..1f,
                modifier = Modifier.weight(1f)
            )
            Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        }
    }
}
