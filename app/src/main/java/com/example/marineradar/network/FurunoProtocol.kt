package com.example.marineradar.network

/**
 * Direkt porterat från mayara-servers `src/lib/brand/furuno/protocol.rs`
 * (verifierad, hårdvarutestad källkod – inga gissningar längre för
 * dessa delar). Se README.md för vilka filer som använts.
 *
 * VIKTIGT: Detta är ett RÅTT BINÄRT protokoll, inte NMEA-text som vi
 * tidigare antog. Discovery sker genom att skicka fasta binära paket
 * till en broadcast-adress och tolka binära svar.
 */
object FurunoProtocol {

    // -------------------------------------------------------------------
    // Nätverk
    // -------------------------------------------------------------------
    const val BASE_PORT = 10000
    const val BEACON_PORT = BASE_PORT + 10 // 10010 – discovery
    const val DATA_PORT = BASE_PORT + 24   // 10024 – spoke-data

    const val BROADCAST_IP = "172.31.255.255"
    /** Multicast-adress för spoke-data (kablade DRS/NXT/FAR-modeller). */
    const val SPOKE_MULTICAST_IP = "239.255.0.2"

    // -------------------------------------------------------------------
    // Discovery-paket (skickas som UDP-broadcast till BROADCAST_IP:BEACON_PORT)
    // -------------------------------------------------------------------

    /** 16 byte: ber radarn skicka sin beacon-rapport (namn). */
    val REQUEST_BEACON_PACKET: ByteArray = byteArrayOf(
        0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x01, 0x00, 0x08, 0x01, 0x00, 0x00, 0x00
    )

    /** 16 byte: ber radarn skicka sin modell-rapport (modellnamn, serienr, fw). */
    val REQUEST_MODEL_PACKET: ByteArray = byteArrayOf(
        0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
        0x14, 0x01, 0x00, 0x08, 0x01, 0x00, 0x00, 0x00
    )

    /** 32 byte: annonserar vår klient för radarn (innehåller ASCII "MAYARA"). */
    val ANNOUNCE_CLIENT_PACKET: ByteArray = byteArrayOf(
        0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x01, 0x00, 0x18, 0x01, 0x00, 0x00, 0x00,
        'M'.code.toByte(), 'A'.code.toByte(), 'Y'.code.toByte(), 'A'.code.toByte(),
        'R'.code.toByte(), 'A'.code.toByte(), 0x00, 0x00,
        0x01, 0x01, 0x00, 0x02, 0x00, 0x01, 0x00, 0x12
    )

    // -------------------------------------------------------------------
    // Svarsformat
    // -------------------------------------------------------------------

    /** De första 11 byten i ett 32-byte beacon-svar (namnrapport). */
    val BEACON_REPORT_HEADER: ByteArray = byteArrayOf(
        0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00
    )

    const val BEACON_REPORT_MIN_LENGTH = 24 // header(11)+length(1)+filler(4)+name(8)

    /** Modellrapporten är alltid exakt 170 byte. */
    const val MODEL_REPORT_LENGTH = 170

    // FurunoRadarModelReport-layout (från protocol.rs):
    // filler1[24] + model[32] + firmware_versions[32] + firmware_version[32] + serial_no[32] + filler2[18] = 170
    const val MODEL_NAME_OFFSET = 24
    const val MODEL_NAME_LENGTH = 32
    const val SERIAL_NO_OFFSET = 24 + 32 + 32 + 32 // = 120
    const val SERIAL_NO_LENGTH = 32

    // -------------------------------------------------------------------
    // Spoke-header (16 byte "IMO"-format)
    // -------------------------------------------------------------------
    const val FRAME_MAGIC: Int = 0x02
    const val SPOKES_PER_REVOLUTION = 8192

    // -------------------------------------------------------------------
    // Wire-index → meter (NM-läge), porterat från WIRE_INDEX_TABLE i
    // protocol.rs. Icke-sekventiell — index 19/20/21 ligger "utanför
    // ordningen" på riktigt i Furunos firmware.
    // -------------------------------------------------------------------
    private const val NM_METERS = 1852.0
    val WIRE_INDEX_TO_METERS: Map<Int, Int> = mapOf(
        21 to (NM_METERS / 16).toInt(),
        0 to (NM_METERS / 8).toInt(),
        1 to (NM_METERS / 4).toInt(),
        2 to (NM_METERS / 2).toInt(),
        3 to (NM_METERS * 3 / 4).toInt(),
        4 to NM_METERS.toInt(),
        5 to (NM_METERS * 3 / 2).toInt(),
        6 to (NM_METERS * 2).toInt(),
        7 to (NM_METERS * 3).toInt(),
        8 to (NM_METERS * 4).toInt(),
        9 to (NM_METERS * 6).toInt(),
        10 to (NM_METERS * 8).toInt(),
        11 to (NM_METERS * 12).toInt(),
        12 to (NM_METERS * 16).toInt(),
        13 to (NM_METERS * 24).toInt(),
        14 to (NM_METERS * 32).toInt(),
        19 to (NM_METERS * 36).toInt(),
        15 to (NM_METERS * 48).toInt(),
        20 to (NM_METERS * 64).toInt(),
        16 to (NM_METERS * 72).toInt(),
        17 to (NM_METERS * 96).toInt(),
        18 to (NM_METERS * 120).toInt(),
    )

    // -------------------------------------------------------------------
    // Spoke-encoding-konstanter (från protocol.rs)
    // -------------------------------------------------------------------
    const val ENCODING_1_REPEAT_DEFAULT = 0x80 // 0 count betyder 128 upprepningar
    const val ENCODING_3_REPEAT_DEFAULT = 0x40 // 0 count betyder 64 upprepningar
    val SPOKE_ALIGNMENT_MASK = 3.inv()   // avrunda uppåt till 4-byte-gräns
    const val ECHO_FLOOR = 10
    const val PIXEL_VALUES = 252
    const val SPOKE_OUTPUT_LEN = 1024 // motsvarar SPOKE_LEN i protocol.rs

    /** Tolkar en nollterminerad ASCII-byteföljd som text (trimmar bort 0x00 m.m.). */
    fun cString(bytes: ByteArray): String? {
        val end = bytes.indexOfFirst { it == 0.toByte() }.let { if (it == -1) bytes.size else it }
        if (end == 0) return null
        val text = String(bytes, 0, end, Charsets.US_ASCII).trim()
        return text.ifEmpty { null }
    }

    val PART_CODE_TO_MODEL = mapOf(
        "0359235" to "DRS",
        "0359255" to "FAR14x7",
        "0359204" to "FAR21x7",
        "0359321" to "FAR14x7",
        "0359338" to "DRS4DL",
        "0359367" to "DRS4DL",
        "0359281" to "FAR3000",
        "0359286" to "FAR3000",
        "0359477" to "FAR3000",
        "0359360" to "DRS4DNXT",
        "0359421" to "DRS6ANXT",
        "0359329" to "DRS4W",
        "0359355" to "DRS6AXCLASS",
        "0359344" to "FAR15x3",
        "0359397" to "FAR14x6",
        "0359560" to "FAR21x7",
    )
}
