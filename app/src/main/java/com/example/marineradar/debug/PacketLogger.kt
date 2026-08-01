package com.example.marineradar.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PacketLogEntry(
    val timestampMs: Long,
    val direction: Direction,
    val remoteHost: String,
    val remotePort: Int,
    val localPort: Int,
    val length: Int,
    val data: ByteArray
) {
    enum class Direction { RX, TX }

    val timeLabel: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestampMs))

    val hexDump: String
        get() = data.take(256).joinToString(" ") { "%02X".format(it) }

    val asciiPreview: String
        get() = data.take(256).joinToString("") { b ->
            val c = b.toInt().toChar()
            if (c.code in 32..126) c.toString() else "."
        }

    val summary: String
        get() = "$timeLabel  ${direction.name}  $remoteHost:$remotePort → :$localPort  ($length B)"
}

/**
 * Global, lättviktig logg (process-singleton) som samlar all UDP-trafik
 * appen skickar/tar emot, så den kan visas live i Debug-vyn och
 * exporteras för analys. Håller bara de senaste [MAX_ENTRIES] posterna
 * i minnet.
 */
object PacketLogger {
    private const val MAX_ENTRIES = 2000

    private val _entries = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val entries: StateFlow<List<PacketLogEntry>> = _entries.asStateFlow()

    fun log(entry: PacketLogEntry) {
        val updated = (_entries.value + entry).takeLast(MAX_ENTRIES)
        _entries.value = updated
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun exportAsText(): String {
        val sb = StringBuilder()
        sb.appendLine("Marine Radar – trafiklogg (${_entries.value.size} paket)")
        sb.appendLine("=".repeat(60))
        for (e in _entries.value) {
            sb.appendLine(e.summary)
            sb.appendLine("  hex:   ${e.hexDump}")
            sb.appendLine("  ascii: ${e.asciiPreview}")
            sb.appendLine()
        }
        return sb.toString()
    }
}
