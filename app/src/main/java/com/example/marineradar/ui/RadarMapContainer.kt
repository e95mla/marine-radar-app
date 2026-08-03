package com.example.marineradar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.marineradar.map.MapProviderType
import com.example.marineradar.radar.PpiRenderer
import com.google.android.gms.maps.model.LatLng

/**
 * Visar karta+radar-överlägget för vald [provider], med en liten
 * väljare överst för att växla mellan de tillgängliga kartleverantörerna.
 */
@Composable
fun RadarMapContainer(
    provider: MapProviderType,
    onProviderChange: (MapProviderType) -> Unit,
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
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            MapProviderType.entries.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = provider == type,
                    onClick = { onProviderChange(type) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = MapProviderType.entries.size)
                ) {
                    Text(type.displayName)
                }
            }
        }
    }
}
