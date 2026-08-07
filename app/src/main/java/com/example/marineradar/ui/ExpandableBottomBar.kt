package com.example.marineradar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.marineradar.map.MapProviderType
import com.example.marineradar.network.RadarControls

/**
 * Alltid synlig underdel av radar-skärmen: räckvidd (+/-) syns oavsett
 * vad som är expanderat, samt två FASTA ikonknappar (48x48dp, ingen
 * text) som fäller ut respektive inställningspanel. Fast storlek är
 * medvetet – tidigare textknappar (FilterChip) kunde i sällsynta fall
 * klämmas ihop till nästan ingen bredd alls, vilket fick texten att
 * radbrytas tecken för tecken ("K/o/nt/ro/lle/r"). Ikoner utan text kan
 * inte drabbas av det.
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
    mapDarkStyle: Boolean,
    onMapDarkStyleChange: (Boolean) -> Unit,
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
                        onOpacityChange = onMapOpacityChange,
                        darkStyle = mapDarkStyle,
                        onDarkStyleChange = onMapDarkStyleChange
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
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
                        SquareIconToggle(
                            icon = "🗺️",
                            selected = expandedPanel == ExpandedPanel.MAP,
                            onClick = {
                                onExpandedPanelChange(
                                    if (expandedPanel == ExpandedPanel.MAP) ExpandedPanel.NONE else ExpandedPanel.MAP
                                )
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    SquareIconToggle(
                        icon = "📡",
                        selected = expandedPanel == ExpandedPanel.RADAR,
                        onClick = {
                            onExpandedPanelChange(
                                if (expandedPanel == ExpandedPanel.RADAR) ExpandedPanel.NONE else ExpandedPanel.RADAR
                            )
                        }
                    )
                }
            }
        }
    }
}

/** Fast 48x48dp-knapp med bara en ikon, ingen text – kan aldrig radbrytas. */
@Composable
fun SquareIconToggle(icon: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(48.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(icon, fontSize = 20.sp)
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
