@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.marineradar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.marineradar.map.MapProviderType
import com.example.marineradar.radar.PpiRenderer
import com.google.android.gms.maps.model.LatLng

/**
 * Visar karta+radar-överlägget för vald [provider], med en liten
 * väljare överst för att växla mellan de tillgängliga kartleverantörerna
 * och en flytande stäng-knapp som ALLTID ligger ovanpå kartan (löser
 * ett tidigare problem där kartans inbäddade Android-View kunde rendera
 * utanför sitt tilldelade område och blockera resten av UI:t, så det
 * inte gick att lämna kartläget).
 */
@Composable
fun RadarMapContainer(
    provider: MapProviderType,
    onProviderChange: (MapProviderType) -> Unit,
    onClose: () -> Unit,
    renderer: PpiRenderer?,
    boatLocation: LatLng?,
    headingDegrees: Float,
    rangeMeters: Int,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (provider) {
            MapProviderType.OPENSTREETMAP -> OsmRadarMapView(
                renderer = renderer,
                boatLocation = boatLocation,
                headingDegrees = headingDegrees,
                rangeMeters = rangeMeters,
                modifier = Modifier.fillMaxSize()
            )
            MapProviderType.GOOGLE_MAPS -> GoogleMapRadarView(
                renderer = renderer,
                boatLocation = boatLocation,
                headingDegrees = headingDegrees,
                rangeMeters = rangeMeters,
                modifier = Modifier.fillMaxSize()
            )
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
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

        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.7f)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Text("✕ Karta", color = Color.White)
        }
    }
}
