@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.marineradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Kartpanelen innehåller numera BARA det som hör hemma i stunden: hur
 * genomskinlig radarbilden ska vara ovanpå kartan. Kartleverantören
 * (OpenStreetMap/Google) väljs en gång i Inställningar, och kartfiltret
 * (satellit/terräng/sjökort …) byts direkt i bilden via lager-ikonen –
 * se [MapStylePicker]. Det gör att skärmen slipper permanenta kartval.
 */
@Composable
fun MapSettingsPanel(
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
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
