package com.example.marineradar.radar

import com.example.marineradar.debug.FileLogger
import com.example.marineradar.network.FurunoProtocol
import kotlin.math.PI
import kotlin.math.pow

/**
 * En "spoke" är radarns data för EN bäring – en rad pixlar (echo-styrka,
 * 0-255 efter display-mappning) utåt från centrum till radarns maxräckvidd.
 */
data class Spoke(
    val angle: Float,   // radianer, 0..2π
    val range: Float,   // meter
    val intensities: ByteArray
)

/**
 * Spoke-avkodare PORTERAD DIREKT från mayara-serverns
 * `src/lib/brand/furuno/report.rs` (funktionerna `parse_metadata_header`,
 * `process_frame`, `decode_sweep_encoding_{0,1,2,3}`, `map_with_overshoot`,
 * `stretch_spoke`, `wire_to_legend_off`) – detta är INTE längre en
 * gissning, utan en verifierad, hårdvarutestad algoritm.
 *
 * VIKTIGT: encoding-lägena 2 och 3 är delta-kodade mot FÖREGÅENDE spoke
 * (`prevSpoke`), så den här klassen måste vara en enda långlivad instans
 * per session (inte skapas om per paket) – annars blir avkodningen fel
 * efter första spoken.
 *
 * Förenklingar mot originalet (medvetna, för v1 av appen):
 * - Ingen "Tile"-formatstöd (NXT-only, DRS4W använder inte det).
 * - Ingen dual-range (DRS4W har bara en range, `capabilities.dual_range
 *   = false` i settings.rs).
 * - Ingen Target Analyzer/Doppler-färgläggning (DRS4W saknar den
 *   funktionen enligt capabilities-tabellen – `target_analyzer: false`).
 * - Enklare display-mappning (`wireToDisplay`) istället för hela
 *   legend/palette-systemet, men SAMMA gammakurva för lågeffekt-radarer
 *   (DRS4W, DRS) som originalet använder.
 */
class FurunoSpokeDecoder {

    private var prevSpoke: ByteArray = ByteArray(0)
    private var frameCount = 0

    /**
     * Avkodar en hel UDP-frame (kan innehålla flera spokes,
     * `sweep_count` stycken) och returnerar en lista med [Spoke]-objekt.
     */
    fun decodeFrame(data: ByteArray): List<Spoke> {
        if (data.size < 16) {
            FileLogger.log("WARN", "SpokeDecoder: frame för kort (${data.size} B), hoppar över")
            return emptyList()
        }

        val packetType = data[0].toInt() and 0xFF
        if (packetType != FurunoProtocol.FRAME_MAGIC) {
            // Kan vara Tile-format (NXT) eller en icke-spoke-rapport som råkar
            // komma på samma port. DRS4W ska bara skicka IMO-format (0x02).
            FileLogger.log(
                "WARN",
                "SpokeDecoder: okänd packet_type=0x${"%02X".format(packetType)} (väntade 0x02), ${data.size} B"
            )
            return emptyList()
        }

        val meta = parseHeader(data)
        frameCount++
        if (frameCount % 20 == 1) {
            FileLogger.log(
                "INFO",
                "SpokeDecoder: frame #$frameCount sweepCount=${meta.sweepCount} " +
                    "sweepLen=${meta.sweepLen} encoding=${meta.encoding} " +
                    "haveHeading=${meta.haveHeading} range=${meta.range}m scale=${meta.scale} " +
                    "wireIndex=${meta.wireIndex}"
            )
        }

        val spokes = mutableListOf<Spoke>()
        var offset = 16

        for (sweepIdx in 0 until meta.sweepCount) {
            if (data.size - offset < 5) break

            val angleRaw = ((data[offset + 1].toInt() and 0x1F) shl 8) or (data[offset].toInt() and 0xFF)
            // heading (data[offset+2..3]) läses ut men används inte ännu i UI:t.
            offset += 4

            val sweepBytes = data.copyOfRange(offset, data.size)
            val (decoded, used) = when (meta.encoding) {
                0 -> decodeEncoding0(sweepBytes)
                1 -> decodeEncoding1(sweepBytes, meta.sweepLen)
                2 -> if (sweepIdx == 0) {
                    decodeEncoding1(sweepBytes, meta.sweepLen)
                } else {
                    decodeEncoding2(sweepBytes, prevSpoke, meta.sweepLen)
                }
                3 -> decodeEncoding3(sweepBytes, prevSpoke, meta.sweepLen)
                else -> Pair(ByteArray(0), 0)
            }

            var genericSpoke = decoded
            if (genericSpoke.size < meta.sweepLen) {
                genericSpoke = genericSpoke.copyOf(meta.sweepLen) // nollpaddar
            }

            offset += used.coerceAtMost(sweepBytes.size)

            val (stretched, spokeRange) = mapWithOvershoot(genericSpoke, meta.scale, meta.sweepLen, meta.range)
            val displayBytes = ByteArray(stretched.size) { i -> wireToDisplay(stretched[i]) }

            val angleRadians = (angleRaw.toFloat() / FurunoProtocol.SPOKES_PER_REVOLUTION) * (2 * PI).toFloat()
            spokes.add(Spoke(angle = angleRadians, range = spokeRange.toFloat(), intensities = displayBytes))

            prevSpoke = genericSpoke
        }

        return spokes
    }

    // -------------------------------------------------------------------
    // Header-parsning (från parse_metadata_header i report.rs)
    // -------------------------------------------------------------------

    private data class FrameMetadata(
        val sweepCount: Int,
        val sweepLen: Int,
        val encoding: Int,
        val haveHeading: Boolean,
        val wireIndex: Int,
        val range: Int,
        val scale: Int
    )

    private fun parseHeader(data: ByteArray): FrameMetadata {
        val sweepCount = (data[9].toInt() and 0xFF) shr 1
        val sweepLen = ((data[11].toInt() and 0x07) shl 8) or (data[10].toInt() and 0xFF)
        val encoding = (data[11].toInt() and 0x18) shr 3
        val haveHeading = (data[11].toInt() and 0x20) != 0
        val wireIndex = data[12].toInt() and 0x3F
        val range = FurunoProtocol.WIRE_INDEX_TO_METERS[wireIndex] ?: 0
        var scale = ((data[15].toInt() and 0x07) shl 8) or (data[14].toInt() and 0xFF)
        if (scale == 0) scale = sweepLen

        return FrameMetadata(sweepCount, sweepLen, encoding, haveHeading, wireIndex, range, scale)
    }

    // -------------------------------------------------------------------
    // Encoding-lägen (från decode_sweep_encoding_{0,1,2,3} i report.rs)
    // -------------------------------------------------------------------

    private fun decodeEncoding0(sweep: ByteArray): Pair<ByteArray, Int> {
        return Pair(sweep.copyOf(), sweep.size)
    }

    private fun decodeEncoding1(sweep: ByteArray, sweepLen: Int): Pair<ByteArray, Int> {
        val spoke = ArrayList<Byte>(sweepLen)
        var used = 0
        var strength: Byte = 0

        while (spoke.size < sweepLen && used < sweep.size) {
            val b = sweep[used].toInt() and 0xFF
            if (b and 0x01 == 0) {
                strength = sweep[used]
                spoke.add(strength)
            } else {
                var repeat = b shr 1
                if (repeat == 0) repeat = FurunoProtocol.ENCODING_1_REPEAT_DEFAULT
                repeat(repeat.coerceAtMost(sweepLen - spoke.size + 1)) {
                    if (spoke.size < sweepLen) spoke.add(strength)
                }
            }
            used++
        }

        used = (used + 3) and FurunoProtocol.SPOKE_ALIGNMENT_MASK
        return Pair(spoke.toByteArray(), used)
    }

    private fun decodeEncoding2(sweep: ByteArray, prevSpoke: ByteArray, sweepLen: Int): Pair<ByteArray, Int> {
        val spoke = ArrayList<Byte>(sweepLen)
        var used = 0

        while (spoke.size < sweepLen && used < sweep.size) {
            val b = sweep[used].toInt() and 0xFF
            if (b and 0x01 == 0) {
                spoke.add(sweep[used])
            } else {
                var repeat = b shr 1
                if (repeat == 0) repeat = FurunoProtocol.ENCODING_1_REPEAT_DEFAULT
                repeat(repeat.coerceAtMost(sweepLen - spoke.size + 1)) {
                    val i = spoke.size
                    val strength = if (prevSpoke.size > i) prevSpoke[i] else 0
                    if (spoke.size < sweepLen) spoke.add(strength)
                }
            }
            used++
        }

        used = (used + 3) and FurunoProtocol.SPOKE_ALIGNMENT_MASK
        return Pair(spoke.toByteArray(), used)
    }

    private fun decodeEncoding3(sweep: ByteArray, prevSpoke: ByteArray, sweepLen: Int): Pair<ByteArray, Int> {
        val spoke = ArrayList<Byte>(sweepLen)
        var used = 0
        var strength: Byte = 0

        while (spoke.size < sweepLen && used < sweep.size) {
            val b = sweep[used].toInt() and 0xFF
            if (b and 0x03 == 0) {
                strength = sweep[used]
                spoke.add(strength)
            } else {
                var repeat = b shr 2
                if (repeat == 0) repeat = FurunoProtocol.ENCODING_3_REPEAT_DEFAULT

                if (b and 0x01 == 0) {
                    // Kopiera från föregående spoke (delta)
                    repeat(repeat.coerceAtMost(sweepLen - spoke.size + 1)) {
                        val i = spoke.size
                        val s = if (prevSpoke.size > i) prevSpoke[i] else 0
                        if (spoke.size < sweepLen) spoke.add(s)
                    }
                } else {
                    // Upprepa senaste literala värdet
                    repeat(repeat.coerceAtMost(sweepLen - spoke.size + 1)) {
                        if (spoke.size < sweepLen) spoke.add(strength)
                    }
                }
            }
            used++
        }

        used = (used + 3) and FurunoProtocol.SPOKE_ALIGNMENT_MASK
        return Pair(spoke.toByteArray(), used)
    }

    // -------------------------------------------------------------------
    // Sträckning till fast buffertstorlek + overshoot-hantering
    // (från map_with_overshoot / stretch_spoke i report.rs)
    // -------------------------------------------------------------------

    private fun mapWithOvershoot(src: ByteArray, scale: Int, sweepLen: Int, rangeMeters: Int): Pair<ByteArray, Int> {
        val usable = minOf(sweepLen, src.size)
        if (scale == 0 || scale >= usable || rangeMeters == 0) {
            return Pair(stretchSpoke(src, usable.coerceAtLeast(1)), rangeMeters)
        }
        val stretched = stretchSpoke(src, usable)
        val widened = ((rangeMeters.toLong() * usable.toLong()) / scale.toLong()).toInt()
        return Pair(stretched, maxOf(widened, rangeMeters))
    }

    private fun stretchSpoke(src: ByteArray, srcEffective: Int): ByteArray {
        val dstLen = FurunoProtocol.SPOKE_OUTPUT_LEN
        if (src.isEmpty()) return ByteArray(dstLen)
        val effective = srcEffective.coerceAtMost(src.size).coerceAtLeast(1)
        if (effective >= dstLen) return src.copyOf(dstLen)

        val out = ByteArray(dstLen)
        for (i in 0 until dstLen) {
            val j = (i * effective) / dstLen
            out[i] = src[j]
        }
        return out
    }

    // -------------------------------------------------------------------
    // Display-mappning (förenklad wire_to_legend_off, samma gammakurva
    // för lågeffekt-radarer som DRS4W)
    // -------------------------------------------------------------------

    private fun wireToDisplay(raw: Byte): Byte {
        val value = raw.toInt() and 0xFF
        if (value == 0) return 0

        val pixelMax = 254
        val usable = pixelMax - FurunoProtocol.ECHO_FLOOR
        // DRS4W är en lågeffekt-radar – använd samma 18:e-rots-gammakurva
        // som mayara-server för att lyfta fram svaga ekon (annars ser
        // bilden nästan tom ut, eftersom 95% av returer ligger under 64).
        val normalized = value.toDouble() / FurunoProtocol.PIXEL_VALUES
        val mapped = FurunoProtocol.ECHO_FLOOR + (normalized.pow(1.0 / 18.0) * usable)
        return mapped.toInt().coerceIn(0, 255).toByte()
    }
}
