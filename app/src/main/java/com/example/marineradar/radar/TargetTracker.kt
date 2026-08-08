package com.example.marineradar.radar

import com.example.marineradar.debug.FileLogger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Enkel ARPA-liknande målspårning ("MARPA-lite") baserad enbart på
 * radarbilden – DRS4W skickar bara ekostyrka per spoke, ingen målinfo.
 *
 * Pipeline per varv:
 *  1. Ekon över [DETECT_THRESHOLD] samlas i ett grovt polärt rutnät
 *     ([BEARING_BINS] x [RANGE_BINS]).
 *  2. Sammanhängande celler slås ihop till blobbar (8-grannskap, med
 *     wrap-around i bäring).
 *  3. Blobbar associeras med befintliga spår via närmaste granne inom en
 *     grind som skalar med räckvidden.
 *  4. Position/fart filtreras med ett alfa-beta-filter, vilket ger kurs
 *     (COG), fart (SOG) samt CPA/TCPA relativt egen båt i origo.
 *
 * Allt sker i ett relativt, nordorienterat kartesiskt plan med egen båt i
 * origo och avstånd i meter.
 */
class TargetTracker {

    companion object {
        /** Ekostyrka (0-255) som krävs för att en cell ska räknas som mål. */
        const val DETECT_THRESHOLD = 110

        const val BEARING_BINS = 360
        const val RANGE_BINS = 128

        /** Minsta antal celler för att en blob ska anses vara ett riktigt mål (filtrerar brus). */
        private const val MIN_BLOB_CELLS = 3

        /** Största antal celler – större än så är oftast land/regn, inte ett fartyg. */
        private const val MAX_BLOB_CELLS = 900

        private const val ALPHA = 0.45f
        private const val BETA = 0.18f

        /** Antal varv utan träff innan ett spår tas bort. */
        private const val MAX_MISSES = 4

        /** Antal träffar innan spåret visas som bekräftat mål med kurs/fart. */
        private const val ACQUIRE_HITS = 4
    }

    private val grid = IntArray(BEARING_BINS * RANGE_BINS)
    private var lastAngleBin = -1
    private var sweptBins = 0
    private var nextId = 1
    private val tracks = mutableListOf<MutableTrack>()

    private var rangeMeters: Int = 3704

    fun setRange(meters: Int) {
        if (meters > 0 && meters != rangeMeters) {
            rangeMeters = meters
            reset()
        }
    }

    fun reset() {
        grid.fill(0)
        tracks.clear()
        lastAngleBin = -1
        sweptBins = 0
    }

    /**
     * Matar in en avkodad spoke. Returnerar en ny mållista när ett helt
     * varv har samlats in, annars null.
     */
    fun onSpoke(angleRadians: Float, intensities: ByteArray): List<RadarTarget>? {
        val deg = ((Math.toDegrees(angleRadians.toDouble()) % 360.0 + 360.0) % 360.0).toInt()
        val bin = deg.coerceIn(0, BEARING_BINS - 1)

        val n = intensities.size
        if (n > 0) {
            val base = bin * RANGE_BINS
            val cellsPerBin = n.toFloat() / RANGE_BINS
            for (rb in 0 until RANGE_BINS) {
                val from = (rb * cellsPerBin).toInt()
                val to = ((rb + 1) * cellsPerBin).toInt().coerceAtMost(n)
                var peak = 0
                for (i in from until to) {
                    val v = intensities[i].toInt() and 0xFF
                    if (v > peak) peak = v
                }
                grid[base + rb] = peak
            }
        }

        var completed: List<RadarTarget>? = null
        if (lastAngleBin >= 0) {
            var delta = bin - lastAngleBin
            if (delta < 0) delta += BEARING_BINS
            sweptBins += delta
            if (sweptBins >= BEARING_BINS) {
                sweptBins = 0
                completed = processSweep()
            }
        }
        lastAngleBin = bin
        return completed
    }

    // -----------------------------------------------------------------
    // Varvbearbetning
    // -----------------------------------------------------------------

    private fun processSweep(): List<RadarTarget> {
        val blobs = findBlobs()
        val now = System.currentTimeMillis()
        associate(blobs, now)
        grid.fill(0)
        return snapshot()
    }

    private data class Blob(val x: Float, val y: Float, val cells: Int, val strength: Int)

    private fun findBlobs(): List<Blob> {
        val visited = BooleanArray(grid.size)
        val blobs = mutableListOf<Blob>()
        val metersPerBin = rangeMeters.toFloat() / RANGE_BINS
        val stack = ArrayDeque<Int>()

        for (start in grid.indices) {
            if (visited[start] || grid[start] < DETECT_THRESHOLD) continue
            stack.clear()
            stack.addLast(start)
            visited[start] = true
            var cells = 0
            var sumX = 0f
            var sumY = 0f
            var peak = 0

            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val b = idx / RANGE_BINS
                val r = idx % RANGE_BINS
                val value = grid[idx]
                if (value > peak) peak = value
                cells++

                val meters = (r + 0.5f) * metersPerBin
                val rad = Math.toRadians(b.toDouble())
                sumX += (meters * sin(rad)).toFloat()
                sumY += (meters * cos(rad)).toFloat()

                if (cells > MAX_BLOB_CELLS) continue

                for (db in -1..1) for (dr in -1..1) {
                    if (db == 0 && dr == 0) continue
                    val nb = (b + db + BEARING_BINS) % BEARING_BINS
                    val nr = r + dr
                    if (nr < 0 || nr >= RANGE_BINS) continue
                    val nidx = nb * RANGE_BINS + nr
                    if (!visited[nidx] && grid[nidx] >= DETECT_THRESHOLD) {
                        visited[nidx] = true
                        stack.addLast(nidx)
                    }
                }
            }

            if (cells in MIN_BLOB_CELLS..MAX_BLOB_CELLS) {
                blobs.add(Blob(sumX / cells, sumY / cells, cells, peak))
            }
        }
        return blobs
    }

    private fun associate(blobs: List<Blob>, now: Long) {
        val taken = BooleanArray(blobs.size)
        // Grind: 8 % av räckvidden, minst 40 m – ett fartyg hinner inte längre mellan två varv.
        val gate = (rangeMeters * 0.08f).coerceAtLeast(40f)

        for (track in tracks) {
            var best = -1
            var bestDist = gate
            val dtPredict = ((now - track.lastSeenMs) / 1000f).coerceIn(0f, 10f)
            val px = track.x + track.vx * dtPredict
            val py = track.y + track.vy * dtPredict
            for (i in blobs.indices) {
                if (taken[i]) continue
                val d = hypot(blobs[i].x - px, blobs[i].y - py)
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            if (best >= 0) {
                taken[best] = true
                track.update(blobs[best].x, blobs[best].y, blobs[best].cells, blobs[best].strength, now)
            } else {
                track.misses++
            }
        }

        tracks.removeAll { it.misses > MAX_MISSES }

        for (i in blobs.indices) {
            if (taken[i]) continue
            if (tracks.size >= 40) break
            tracks.add(MutableTrack(nextId++, blobs[i].x, blobs[i].y, blobs[i].cells, blobs[i].strength, now))
        }
    }

    private fun snapshot(): List<RadarTarget> = tracks
        .filter { it.hits >= ACQUIRE_HITS && it.misses == 0 }
        .map { it.toTarget() }
        .sortedBy { it.rangeMeters }

    // -----------------------------------------------------------------

    private class MutableTrack(
        val id: Int,
        var x: Float,
        var y: Float,
        var cells: Int,
        var strength: Int,
        var lastSeenMs: Long
    ) {
        var vx = 0f
        var vy = 0f
        var hits = 1
        var misses = 0

        fun update(nx: Float, ny: Float, ncells: Int, nstrength: Int, now: Long) {
            val dt = ((now - lastSeenMs) / 1000f).coerceIn(0.3f, 10f)
            val px = x + vx * dt
            val py = y + vy * dt
            val rx = nx - px
            val ry = ny - py
            x = px + ALPHA * rx
            y = py + ALPHA * ry
            vx += BETA * rx / dt
            vy += BETA * ry / dt
            cells = ncells
            strength = nstrength
            lastSeenMs = now
            hits++
            misses = 0
        }

        fun toTarget(): RadarTarget {
            val range = hypot(x, y)
            val bearing = ((Math.toDegrees(atan2(x.toDouble(), y.toDouble())) + 360.0) % 360.0).toFloat()
            val speedMs = hypot(vx, vy)
            val course = if (speedMs > 0.3f) {
                ((Math.toDegrees(atan2(vx.toDouble(), vy.toDouble())) + 360.0) % 360.0).toFloat()
            } else null

            // CPA/TCPA relativt egen båt i origo (relativ rörelse).
            var cpa = range
            var tcpa = 0f
            val v2 = vx * vx + vy * vy
            if (v2 > 0.01f) {
                val t = -(x * vx + y * vy) / v2
                if (t > 0f) {
                    tcpa = t
                    cpa = hypot(x + vx * t, y + vy * t)
                } else {
                    tcpa = 0f
                    cpa = range
                }
            }

            return RadarTarget(
                id = id,
                rangeMeters = range,
                bearingDegrees = bearing,
                courseDegrees = course,
                speedKnots = if (course != null) speedMs * 1.94384f else null,
                cpaMeters = cpa,
                tcpaSeconds = tcpa,
                strength = strength,
                sizeCells = cells,
                relX = x,
                relY = y
            )
        }
    }
}

/** Ett spårat mål, uttryckt relativt egen båt (origo, norr uppåt). */
data class RadarTarget(
    val id: Int,
    val rangeMeters: Float,
    val bearingDegrees: Float,
    /** Kurs över grund i grader, null tills målet rört sig mätbart. */
    val courseDegrees: Float?,
    val speedKnots: Float?,
    /** Närmaste passageavstånd (Closest Point of Approach) i meter. */
    val cpaMeters: Float,
    /** Tid till CPA i sekunder. */
    val tcpaSeconds: Float,
    val strength: Int,
    val sizeCells: Int,
    val relX: Float,
    val relY: Float
) {
    val rangeNm: Float get() = rangeMeters / 1852f

    /** Kollisionsrisk: passerar nära och gör det inom rimlig tid. */
    val isDangerous: Boolean
        get() = tcpaSeconds > 1f && tcpaSeconds < 600f && cpaMeters < 300f
}
