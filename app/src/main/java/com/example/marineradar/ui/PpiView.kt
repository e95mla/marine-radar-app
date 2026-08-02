package com.example.marineradar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.example.marineradar.radar.PpiRenderer
import kotlinx.coroutines.delay

/**
 * Visar [PpiRenderer]s bitmap. Bitmappen ritas kontinuerligt i bakgrunden
 * (en radiell linje per mottagen spoke, se [PpiRenderer.drawSpoke]) –
 * den här composabeln behöver bara sampla den med en fast, låg
 * bildfrekvens (~15 fps) istället för att recomponera på varje enskild
 * spoke, vilket annars skulle vara långsamt och onödigt (radarn kan
 * skicka hundratals spokes per sekund).
 */
@Composable
fun PpiView(renderer: PpiRenderer?, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(renderer) {
        if (renderer == null) return@LaunchedEffect
        while (true) {
            frame++
            delay(66) // ~15 fps
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (renderer != null) {
            key(frame) {
                Image(
                    bitmap = renderer.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
