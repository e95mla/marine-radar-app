package com.example.marineradar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.marineradar.radar.PpiRenderer
import com.example.marineradar.settings.RadarSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Visar [PpiRenderer]s bitmap som en kvadrat centrerad i tillgängligt
 * utrymme, med pinch-to-zoom, pan och (nytt) en markör: ett tryck i bilden
 * sätter EBL/VRM, dvs. visar bäring och avstånd till den punkten. Dubbeltryck
 * nollställer både markör och zoom.
 *
 * North-up löses genom att rotera HELA det kvadratiska innehållet (bitmap +
 * överlägg) med kompasskursen – radarn skickar bilden fören-upp.
 */
@Composable
fun PpiView(
    renderer: PpiRenderer?,
    settings: RadarSettings,
    modifier: Modifier = Modifier,
    targets: List<com.example.marineradar.radar.RadarTarget> = emptyList(),
    rangeMeters: Int = 3704,
    headingDegrees: Float = 0f,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null
) {
    var frame by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(renderer) {
        if (renderer == null) return@LaunchedEffect
        while (true) {
            frame++
            delay(66) // ~15 fps – frikopplat från hur ofta spokes faktiskt kommer in
        }
    }

    val rotation = if (settings.northUp) -headingDegrees else 0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                kotlinx.coroutines.coroutineScope {
                    launch {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset += pan
                        }
                    }
                    launch {
                        detectTapGestures(
                            onTap = { cursor = it },
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                                cursor = null
                            }
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (renderer != null) {
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                        rotationZ = rotation
                    )
            ) {
                key(frame) {
                    Image(
                        bitmap = renderer.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                TargetOverlay(
                    targets = targets,
                    rangeMeters = rangeMeters,
                    headingDegrees = headingDegrees,
                    settings = settings,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // EBL/VRM-avläsning för markören.
        val c = cursor
        if (c != null && boxSize.width > 0) {
            val cx = boxSize.width / 2f
            val cy = boxSize.height / 2f
            val side = minOf(boxSize.width, boxSize.height).toFloat()
            val maxRadius = side / 2f - 6f
            // Kompensera för zoom/pan så avläsningen stämmer även inzoomat.
            val dx = (c.x - cx - offset.x) / scale
            val dy = (c.y - cy - offset.y) / scale
            val pixelRange = hypot(dx, dy)
            val meters = pixelRange / maxRadius * rangeMeters
            var brg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
            brg = (brg % 360f + 360f) % 360f
            if (settings.northUp) brg = (brg % 360f + 360f) % 360f else brg = ((brg + headingDegrees) % 360f + 360f) % 360f

            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 64.dp, end = 8.dp)
            ) {
                Text(
                    "EBL %03.0f°  VRM %.2f NM".format(brg, meters / 1852f),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (scale > 1.05f) {
            Text(
                text = "${"%.1f".format(scale)}× – dubbeltryck för att återställa",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            )
        }

        if (onToggleFullscreen != null) {
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Text(
                    if (isFullscreen) "⤡" else "⤢",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
    }
}
