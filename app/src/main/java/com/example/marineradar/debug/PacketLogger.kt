package com.example.marineradar.debug

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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
    private const val MAX_FILE_SIZE_BYTES = 3 * 1024 * 1024 // 3 MB, rullar över därefter

    private val _entries = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val entries: StateFlow<List<PacketLogEntry>> = _entries.asStateFlow()

    private var packetFile: File? = null

    /** Paketinsamling sker bara i felsökningsläge – annars slösad CPU/disk. */
    @Volatile
    var enabled: Boolean = false

    /** Måste anropas en gång vid appstart för att aktivera diskpersistens. */
    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        packetFile = File(dir, "packets.log")
    }

    fun log(entry: PacketLogEntry) {
        if (!enabled) return
        val updated = (_entries.value + entry).takeLast(MAX_ENTRIES)
        _entries.value = updated
        persist(entry)
    }

    /** Läser in tidigare sessioners paketlogg som text (för visning/export). */
    fun readPersistedLog(maxChars: Int = 20000): String {
        val file = packetFile ?: return "(diskloggning ej initierad)"
        if (!file.exists()) return "(ingen tidigare loggfil hittad)"
        val text = file.readText()
        return if (text.length > maxChars) "…(avkortat)…\n" + text.takeLast(maxChars) else text
    }

    private fun persist(entry: PacketLogEntry) {
        val file = packetFile ?: return
        try {
            if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                val kept = file.readText().takeLast(MAX_FILE_SIZE_BYTES / 2)
                file.writeText(kept)
            }
            file.appendText(entry.summary + "  hex=" + entry.hexDump.take(120) + "\n")
        } catch (_: Exception) {
            // Diskloggning får aldrig krascha appen.
        }
    }

    fun clear() {
        _entries.value = emptyList()
        try {
            packetFile?.writeText("")
        } catch (_: Exception) { }
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
