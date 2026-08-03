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
import androidx.compose.ui.unit.dp
import com.example.marineradar.radar.PpiRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Visar [PpiRenderer]s bitmap som en kvadrat centrerad i tillgängligt
 * utrymme (så bilden alltid fyller antingen bredden eller höjden helt,
 * utan onödigt tomrum), med pinch-to-zoom och pan. [onToggleFullscreen]
 * anropas när användaren trycker på helskärmsknappen – MainActivity
 * ansvarar för att faktiskt växla layouten.
 *
 * Bitmappen ritas kontinuerligt i bakgrunden (en radiell linje per
 * mottagen spoke) – den här composabeln samplar den med en fast, låg
 * bildfrekvens (~15 fps) istället för att recomponera på varje enskild
 * spoke.
 */
@Composable
fun PpiView(
    renderer: PpiRenderer?,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null
) {
    var frame by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(renderer) {
        if (renderer == null) return@LaunchedEffect
        while (true) {
            frame++
            delay(66) // ~15 fps – frikopplat från hur ofta spokes faktiskt kommer in
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                kotlinx.coroutines.coroutineScope {
                    launch {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset += pan
                        }
                    }
                    launch {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (renderer != null) {
            key(frame) {
                Image(
                    bitmap = renderer.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
        }

        if (scale > 1.05f) {
            Text(
                text = "${"%.1f".format(scale)}× – dubbeltryck ⤡ för att återställa",
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
