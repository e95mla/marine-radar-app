package com.example.marineradar.radar

/**
 * En "spoke" är radarns data för EN bäring – en rad pixlar (echo-styrka)
 * utåt från centrum till radarns maxräckvidd, som sedan ritas i en
 * roterande sektor för att bygga upp den klassiska PPI-bilden.
 *
 * @param angle vinkel i radians, 0 = rakt fram (eller norr, beroende på
 *   headingläge på radarn)
 * @param range räckvidd i meter som denna spoke representerar
 * @param intensities echo-styrka per pixel, 0..255, från centrum och utåt
 */
data class Spoke(
    val angle: Float,
    val range: Float,
    val intensities: ByteArray
)

/**
 * Furunos exakta binärformat för spoke-paket är inte offentligt
 * dokumenterat. Formatet nedan är en RIMLIG UTGÅNGSPUNKT byggd på hur
 * andra marina radar (Navico/Garmin) och Furunos egna DRS-NXT-serie
 * strukturerar sina UDP-spoke-paket (header med vinkel/räckvidd följt
 * av RLE-komprimerad eller rå echo-data) – men den MÅSTE verifieras och
 * troligen justeras mot riktig trafik från din DRS4W innan bilden blir
 * korrekt.
 *
 * Rekommenderad process för att färdigställa den:
 * 1. Fånga trafik med Wireshark/tcpdump medan officiella "Marine Radar"
 *    (iOS) eller mayara-server pratar med radarn
 *    (se docs/capturing-traffic.md i MarineYachtRadar/mayara-server).
 * 2. Jämför bytelayouten i fångsten med [decode] nedan och justera
 *    HEADER_SIZE, offsets och ev. RLE-uppackningen.
 * 3. Testa med [SpokeDecoderTest]-liknande enhetstester (lägg egna
 *    riktiga paket som testdata).
 */
object SpokeDecoder {

    // TODO: justera efter verklig paketstruktur.
    private const val HEADER_SIZE = 8
    private const val ANGLE_OFFSET = 0 // 2 byte, little-endian, 0..4095 = full varv
    private const val RANGE_OFFSET = 2 // 4 byte, little-endian, meter
    private const val ANGLE_STEPS_PER_REVOLUTION = 4096

    fun decode(raw: ByteArray): Spoke? {
        if (raw.size <= HEADER_SIZE) return null

        val angleRaw = readUInt16LE(raw, ANGLE_OFFSET)
        val rangeRaw = readUInt32LE(raw, RANGE_OFFSET)

        val angle = (angleRaw.toFloat() / ANGLE_STEPS_PER_REVOLUTION) * (2 * Math.PI).toFloat()
        val range = rangeRaw.toFloat()

        val payload = raw.copyOfRange(HEADER_SIZE, raw.size)
        val intensities = unpackRunLength(payload)

        return Spoke(angle = angle, range = range, intensities = intensities)
    }

    /**
     * Många marina radarprotokoll RLE-komprimerar spoke-data: en byte
     * anger antal upprepningar, nästa byte är själva echo-värdet.
     * Placeholder-implementation – justera efter verkligt format
     * (kan t.ex. istället vara 4-bitars nibbles, eller helt okomprimerat).
     */
    private fun unpackRunLength(payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(payload.size * 2)
        var i = 0
        while (i + 1 < payload.size) {
            val count = payload[i].toInt() and 0xFF
            val value = payload[i + 1]
            repeat(count.coerceIn(1, 255)) { out.add(value) }
            i += 2
        }
        return out.toByteArray()
    }

    private fun readUInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}
