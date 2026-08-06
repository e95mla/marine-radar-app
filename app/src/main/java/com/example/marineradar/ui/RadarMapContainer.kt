package com.example.marineradar.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.marineradar.map.MapProviderType
import com.example.marineradar.radar.PpiRenderer
import com.google.android.gms.maps.model.LatLng

/**
 * Visar karta+radar-överlägget för vald [provider]. Rena flytande
 * kontroller (kartval, genomskinlighet, stäng, helskärm) hanteras nu
 * centralt i [com.example.marineradar.MainActivity] som en gemensam
 * overlay ovanpå både den här och den vanliga PPI-vyn, så de beter sig
 * konsekvent oavsett läge.
 */
@Composable
fun RadarMapContainer(
    provider: MapProviderType,
    renderer: PpiRenderer?,
    boatLocation: LatLng?,
    headingDegrees: Float,
    rangeMeters: Int,
    opacity: Float,
    modifier: Modifier = Modifier
) {
    when (provider) {
        MapProviderType.OPENSTREETMAP -> OsmRadarMapView(
            renderer = renderer,
            boatLocation = boatLocation,
            headingDegrees = headingDegrees,
            rangeMeters = rangeMeters,
            opacity = opacity,
            modifier = modifier.fillMaxSize()
        )
        MapProviderType.GOOGLE_MAPS -> GoogleMapRadarView(
            renderer = renderer,
            boatLocation = boatLocation,
            headingDegrees = headingDegrees,
            rangeMeters = rangeMeters,
            opacity = opacity,
            modifier = modifier.fillMaxSize()
        )
    }
}
