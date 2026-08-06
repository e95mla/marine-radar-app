package com.example.marineradar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.marineradar.map.MapProviderType
import com.example.marineradar.network.RadarControls

/**
 * Alltid synlig underdel av radar-skärmen: räckvidd (+/-) syns oavsett
 * vad som är expanderat, samt två knappar som fäller ut respektive
 * inställningspanel ("Karta" och "Kontroller") ovanför den här raden.
 * Bara en panel öppen åt gången för att hålla det städat.
 */
@Composable
fun ExpandableBottomBar(
    showMapButton: Boolean,
    expandedPanel: ExpandedPanel,
    onExpandedPanelChange: (ExpandedPanel) -> Unit,
    rangeMeters: Int,
    onRangeStep: (Boolean) -> Unit,
    radarControls: RadarControls,
    onPowerToggle: (Boolean) -> Unit,
    onGainChange: (Boolean, Int) -> Unit,
    onSeaChange: (Boolean, Int) -> Unit,
    onRainChange: (Boolean, Int) -> Unit,
    mapProvider: MapProviderType,
    onMapProviderChange: (MapProviderType) -> Unit,
    mapOpacity: Float,
    onMapOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp
    ) {
        Column {
            AnimatedVisibility(
                visible = expandedPanel == ExpandedPanel.MAP && showMapButton,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    MapSettingsPanel(
                        provider = mapProvider,
                        onProviderChange = onMapProviderChange,
                        opacity = mapOpacity,
                        onOpacityChange = onMapOpacityChange
                    )
                }
            }
            AnimatedVisibility(
                visible = expandedPanel == ExpandedPanel.RADAR,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    RadarControlPanel(
                        controls = radarControls,
                        onPowerToggle = onPowerToggle,
                        onGainChange = onGainChange,
                        onSeaChange = onSeaChange,
                        onRainChange = onRainChange
                    )
                }
            }

            if (expandedPanel != ExpandedPanel.NONE) {
                Divider()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Räckvidd – ALLTID synlig, oavsett vilken panel som är öppen.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onRangeStep(false) }) { Text("－") }
                    Text(
                        formatRangeMeters(rangeMeters),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    OutlinedButton(onClick = { onRangeStep(true) }) { Text("＋") }
                }

                Row {
                    if (showMapButton) {
                        FilterChip(
                            selected = expandedPanel == ExpandedPanel.MAP,
                            onClick = {
                                onExpandedPanelChange(
                                    if (expandedPanel == ExpandedPanel.MAP) ExpandedPanel.NONE else ExpandedPanel.MAP
                                )
                            },
                            label = { Text("🗺️ Karta") },
                            colors = FilterChipDefaults.filterChipColors()
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    FilterChip(
                        selected = expandedPanel == ExpandedPanel.RADAR,
                        onClick = {
                            onExpandedPanelChange(
                                if (expandedPanel == ExpandedPanel.RADAR) ExpandedPanel.NONE else ExpandedPanel.RADAR
                            )
                        },
                        label = { Text("📡 Kontroller") },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }
        }
    }
}

enum class ExpandedPanel { NONE, MAP, RADAR }

private fun formatRangeMeters(meters: Int): String {
    val nm = meters / 1852.0
    return when {
        nm >= 1.0 -> "%.1f NM".format(nm)
        else -> "%.0f m".format(meters.toDouble())
    }
}
