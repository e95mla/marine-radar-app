package com.example.marineradar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.example.marineradar.map.MapStyle
import com.example.marineradar.radar.PpiRenderer
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay
import kotlin.math.cos

/**
 * Google Maps-vy med radarbilden som ett roterande, korrekt
 * positionerat/skalat overlay ovanpå kartan. Kräver en giltig Google
 * Maps API-nyckel (se README.md) – utan en visas kartan tom/grå, men
 * appen kraschar inte.
 */
@Composable
fun GoogleMapRadarView(
    renderer: PpiRenderer?,
    boatLocation: LatLng?,
    headingDegrees: Float,
    rangeMeters: Int,
    opacity: Float = 0.6f,
    style: MapStyle = MapStyle.DARK,
    settings: com.example.marineradar.settings.RadarSettings =
        com.example.marineradar.settings.RadarSettings(),
    targets: List<com.example.marineradar.radar.RadarTarget> = emptyList(),
    modifier: Modifier = Modifier
) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(renderer) {
        if (renderer == null) return@LaunchedEffect
        while (true) {
            frame++
            delay(150) // kartan behöver inte lika hög bildfrekvens som PPI-vyn
        }
    }

    val cameraPositionState = rememberCameraPositionState()
    var hasCenteredOnce by remember { mutableIntStateOf(0) }
    LaunchedEffect(boatLocation) {
        val loc = boatLocation ?: return@LaunchedEffect
        if (hasCenteredOnce == 0) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(loc, 15f)
            hasCenteredOnce = 1
        }
    }

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = googleMapType(style))
        ) {
            if (renderer != null && boatLocation != null) {
                key(frame) {
                    GroundOverlay(
                        position = GroundOverlayPosition.create(
                            location = boatLocation,
                            width = (rangeMeters * 2).toFloat()
                        ),
                        image = BitmapDescriptorFactory.fromBitmap(renderer.bitmap),
                        bearing = headingDegrees,
                        transparency = (1f - opacity).coerceIn(0f, 1f)
                    )
                }
            }
            boatLocation?.let {
                Marker(state = MarkerState(position = it), title = "Din position")
            }
        }

        // Ringar/kurslinje/mål ovanpå kartan – samma lager som i PPI-vyn, men
        // skalat efter kartans zoom så att inställningarna syns i kartläge.
        val loc = boatLocation
        if (loc != null) {
            val projection = cameraPositionState.projection
            val screenPoint = remember(projection, loc, frame, cameraPositionState.position) {
                try { projection?.toScreenLocation(loc) } catch (_: Exception) { null }
            }
            // 156543-formeln ger meter per *karta*-pixel (256 px-kakel, dvs dp
            // på Android). Skärmpixlar är density gånger fler – utan den
            // korrigeringen blev ringarna för små och hamnade fel i förhållande
            // till ekobilden vid zoom.
            val density = LocalDensity.current.density
            val metersPerPixel = remember(cameraPositionState.position.zoom, loc, density) {
                ((156543.03392 * cos(Math.toRadians(loc.latitude)) /
                    Math.pow(2.0, cameraPositionState.position.zoom.toDouble())) / density).toFloat()
            }
            if (screenPoint != null && metersPerPixel > 0f) {
                MapRadarDecor(
                    centerPx = Offset(screenPoint.x.toFloat(), screenPoint.y.toFloat()),
                    radiusPx = rangeMeters / metersPerPixel,
                    headingDegrees = headingDegrees,
                    rangeMeters = rangeMeters,
                    settings = settings,
                    targets = targets
                )
            }
        }

        if (boatLocation == null) {
            Text(
                "Väntar på GPS-position …",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * Översätter appens [MapStyle] till Google Maps egna karttyper. Google
 * saknar motsvarighet till sjökort/natur, så de faller tillbaka på
 * närmaste variant – vill man ha riktiga sjökortsdetaljer väljer man
 * OpenStreetMap som kartleverantör i Inställningar.
 */
private fun googleMapType(style: MapStyle): MapType = when (style) {
    MapStyle.STANDARD, MapStyle.NAUTICAL -> MapType.NORMAL
    MapStyle.DARK -> MapType.NORMAL
    MapStyle.SATELLITE -> MapType.HYBRID
    MapStyle.TERRAIN, MapStyle.NATURE -> MapType.TERRAIN
}
