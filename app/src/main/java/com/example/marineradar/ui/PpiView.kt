package com.example.marineradar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.marineradar.radar.ANGLE_STEPS
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Klassisk PPI-vy (Plan Position Indicator): centrum = eget fartyg,
 * varje spoke ritas som en linje utåt där färgen/alphan representerar
 * echo-styrkan i varje pixel längs linjen.
 */
@Composable
fun PpiView(spokeBuffer: Array<ByteArray>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = min(size.width, size.height) / 2f

        // Rangeringar (avståndscirklar) som referens
        val rings = 4
        for (i in 1..rings) {
            drawCircle(
                color = Color(0xFF0B3B0B),
                radius = maxRadius * i / rings,
                center = center,
                style = Stroke(width = 1f)
            )
        }

        for (angleIndex in spokeBuffer.indices) {
            val intensities = spokeBuffer[angleIndex]
            if (intensities.isEmpty()) continue

            val angle = (angleIndex.toFloat() / ANGLE_STEPS) * (2 * Math.PI).toFloat() -
                (Math.PI / 2).toFloat() // 0 rad = rakt upp på skärmen
            val dx = cos(angle)
            val dy = sin(angle)

            val step = maxRadius / intensities.size
            for (pixelIndex in intensities.indices) {
                val strength = intensities[pixelIndex].toInt() and 0xFF
                if (strength == 0) continue

                val r = pixelIndex * step
                val point = Offset(center.x + dx * r, center.y + dy * r)
                val alpha = (strength / 255f).coerceIn(0f, 1f)

                drawCircle(
                    color = Color(0f, 1f, 0f, alpha),
                    radius = step.coerceAtLeast(1.5f),
                    center = point
                )
            }
        }

        // Egen position i centrum
        drawCircle(
            color = Color.White,
            radius = 4f,
            center = center,
            style = Stroke(width = 2f, cap = StrokeCap.Round)
        )
    }
}
