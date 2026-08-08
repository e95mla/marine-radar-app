package com.example.marineradar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.marineradar.map.MapStyle

/**
 * Standardikonen för kartlager ("layers") direkt i kartbilden – tryck så
 * fälls listan med kartfilter ut (standard, mörk, satellit, terräng,
 * natur, sjökort). Valet sparas som vanlig inställning och gäller även
 * nästa gång appen startas.
 */
@Composable
fun MapStylePicker(
    style: MapStyle,
    onStyleChange: (MapStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        SquareIconToggle(
            icon = "\u2630",
            selected = expanded,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MapStyle.values().forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${option.icon}  ${option.displayName}",
                            fontWeight = if (option == style) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == style) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = {
                        onStyleChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
