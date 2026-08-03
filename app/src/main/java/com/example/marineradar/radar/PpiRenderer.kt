package com.example.marineradar.radar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ritar radarbilden till en delad [Bitmap] istället för att rita
 * tusentals enskilda `drawCircle`-anrop i Compose varje frame (vilket
 * var långsamt och gav visuella artefakter – t.ex. den stora gröna
 * "diamanten" som dök upp i tidigare version). Varje inkommen spoke
 * ritas som en tunn radiell linje direkt in i bitmappen; Compose-vyn
 * behöver bara visa bitmappen med jämna mellanrum (se [tick]).
 */
class PpiRenderer(private val sizePx: Int = 720) {

    val bitmap: Bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val spokePaint = Paint().apply {
        strokeWidth = 2f
        isAntiAlias = false
    }
    private val ringPaint = Paint().apply {
        color = Color.argb(70, 0, 100, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    private val centerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    val center = sizePx / 2f
    val maxRadius = sizePx / 2f - 6f

    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick.asStateFlow()

    init {
        clear()
    }

    /**
     * Rensar bilden och ritar om avståndsringar + centrummarkör.
     * Bakgrunden är TRANSPARENT (inte svart) med flit – det gör att
     * samma bitmap fungerar både i den vanliga PPI-vyn (som har svart
     * bakgrund bakom, se [com.example.marineradar.ui.PpiView]) OCH som
     * genomskinligt kartöverlägg ovanpå Google Maps/OpenStreetMap.
     */
    fun clear() {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        for (i in 1..4) {
            canvas.drawCircle(center, center, maxRadius * i / 4, ringPaint)
        }
        canvas.drawCircle(center, center, 4f, centerPaint)
        bump()
    }

    /**
     * Ritar en spoke som en radiell linje med varierande intensitet.
     * [intensities] förväntas vara [FurunoProtocol]-avkodad display-data
     * (0-255, redan gammakorrigerad av [FurunoSpokeDecoder]), storlek
     * enligt `FurunoProtocol.SPOKE_OUTPUT_LEN`.
     */
    fun drawSpoke(angleRadians: Float, intensities: ByteArray) {
        val adjusted = angleRadians - (Math.PI / 2).toFloat() // 0 rad = rakt upp på skärmen
        val dx = cos(adjusted)
        val dy = sin(adjusted)

        val n = intensities.size
        if (n == 0) return

        var i = 0
        while (i < n) {
            val value = intensities[i].toInt() and 0xFF
            if (value == 0) {
                i++
                continue
            }
            var j = i
            while (j < n && (intensities[j].toInt() and 0xFF) == value) j++

            val r0 = maxRadius * i / n
            val r1 = maxRadius * j / n
            spokePaint.color = Color.argb(255, 0, value, 0)
            canvas.drawLine(
                center + dx * r0, center + dy * r0,
                center + dx * r1, center + dy * r1,
                spokePaint
            )
            i = j
        }
        bump()
    }

    private fun bump() {
        _tick.value = _tick.value + 1
    }
}
