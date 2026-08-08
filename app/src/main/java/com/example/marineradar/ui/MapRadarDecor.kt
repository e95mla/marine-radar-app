package com.example.marineradar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.example.marineradar.radar.RadarTarget
import com.example.marineradar.settings.RadarSettings
import kotlin.math.roundToInt

/**
 * Ritar avståndsringar, kurslinje och mål ovanpå en karta.
 *
 * I kartläge ritas själva ekobilden halvgenomskinlig, och tidigare låg
 * ringarna/kurslinjen inbrända i den bilden – vilket gjorde dem i praktiken
 * osynliga mot kartan. Här ritas de i stället som ett eget, helt opakt
 * Compose-lager som placeras och skalas efter kartans projektion, så att
 * inställningarna får samma effekt som i den vanliga PPI-vyn.
 */
@Composable
fun MapRadarDecor(
    centerPx: Offset?,
    radiusPx: Float,
    headingDegrees: Float,
    rangeMeters: Int,
    settings: RadarSettings,
    targets: List<RadarTarget>,
    modifier: Modifier = Modifier
) {
    if (centerPx == null || radiusPx <= 1f || radiusPx.isNaN() || radiusPx.isInfinite()) return
    val density = LocalDensity.current
    val sideDp = with(density) { (radiusPx * 2f).toDp() }

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (centerPx.x - radiusPx).roundToInt(),
                    (centerPx.y - radiusPx).roundToInt()
                )
            }
            .size(sideDp)
            .graphicsLayer(rotationZ = headingDegrees)
    ) {
        TargetOverlay(
            targets = targets,
            rangeMeters = rangeMeters,
            headingDegrees = headingDegrees,
            settings = settings,
            modifier = Modifier.fillMaxSize()
        )
    }
}
