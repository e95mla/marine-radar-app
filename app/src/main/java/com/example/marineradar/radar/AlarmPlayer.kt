package com.example.marineradar.radar

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Enkelt larm för vaktzon och CPA-varning: en kort pipton och/eller
 * vibration. Medvetet minimalt – inga ljudfiler att paketera, och
 * [ToneGenerator] fungerar även när telefonen är i tyst läge om
 * larmströmmen är påslagen.
 *
 * Larmet är "kant-triggat" av anroparen (se RadarViewModel): det ljuder
 * när ett nytt mål kommer in i zonen, inte kontinuerligt.
 */
class AlarmPlayer(private val context: Context) {

    private var tone: ToneGenerator? = null

    private fun toneGenerator(): ToneGenerator? {
        if (tone == null) {
            tone = runCatching {
                ToneGenerator(AudioManager.STREAM_ALARM, 90)
            }.getOrNull()
        }
        return tone
    }

    fun alert(sound: Boolean, vibrate: Boolean) {
        if (sound) {
            runCatching { toneGenerator()?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600) }
        }
        if (vibrate) {
            runCatching {
                val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(400)
                }
            }
        }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }
}
