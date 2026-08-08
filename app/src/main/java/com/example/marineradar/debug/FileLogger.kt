package com.example.marineradar.debug

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Skriver app-loggar och kraschrapporter till disk, så de finns kvar
 * mellan sessioner och kan visas/delas från Felsöknings-fliken – även
 * efter en krasch (då hinner man ju inte se det som bara låg i minnet).
 *
 * Filerna hamnar i appens privata lagring (context.filesDir), dvs.
 * inget som syns för andra appar.
 */
object FileLogger {
    private const val MAX_LOG_SIZE_BYTES = 2 * 1024 * 1024 // 2 MB, rullar över därefter
    private lateinit var logFile: File
    private lateinit var crashFile: File
    private var initialized = false

    /**
     * Felsökningsläget. Avstängt = bara WARN/ERROR/FATAL skrivs till disk.
     * Rutinmässig INFO-loggning i bakgrunden hela tiden är onödigt slitage
     * på flashminnet och gör loggen oläslig när något faktiskt går fel.
     */
    @Volatile
    var debugEnabled: Boolean = false

    fun init(context: Context) {
        if (initialized) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "app.log")
        crashFile = File(dir, "last_crash.log")
        initialized = true

        installCrashHandler()
        log("INFO", "FileLogger initierad. Loggfil: ${logFile.absolutePath}")
    }

    fun log(level: String, message: String, throwable: Throwable? = null) {
        if (!initialized) return
        if (!debugEnabled && level !in ALWAYS_LOGGED_LEVELS) return
        try {
            rotateIfNeeded()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val line = buildString {
                append("$time [$level] $message")
                if (throwable != null) {
                    append("\n")
                    append(throwable.stackTraceToString())
                }
            }
            logFile.appendText(line + "\n")
        } catch (_: Exception) {
            // Om själva loggningen misslyckas ska appen ändå inte krascha.
        }
    }

    fun readLog(maxChars: Int = 20000): String {
        if (!initialized || !logFile.exists()) return "(ingen logg ännu)"
        val text = logFile.readText()
        return if (text.length > maxChars) "…(avkortat)…\n" + text.takeLast(maxChars) else text
    }

    fun readLastCrash(): String? {
        if (!initialized || !crashFile.exists()) return null
        return crashFile.readText()
    }

    fun clearLog() {
        if (!initialized) return
        logFile.writeText("")
    }

    private val ALWAYS_LOGGED_LEVELS = setOf("WARN", "ERROR", "FATAL")

    fun logFilePath(): String = if (initialized) logFile.absolutePath else ""

    private fun rotateIfNeeded() {
        if (::logFile.isInitialized && logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
            val kept = logFile.readText().takeLast(MAX_LOG_SIZE_BYTES / 2)
            logFile.writeText(kept)
        }
    }

    /**
     * Fångar okrascher (uncaught exceptions) INNAN appen dör, skriver
     * en fullständig kraschrapport till disk, och låter sedan systemets
     * vanliga hantering ta vid (appen kraschar fortfarande – men nästa
     * gång du öppnar appen kan du läsa exakt varför under Felsökning).
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                crashFile.writeText(
                    "Krasch: $time\nTråd: ${thread.name}\n\n${throwable.stackTraceToString()}"
                )
                log("FATAL", "Okrascher fångad, se last_crash.log", throwable)
            } catch (_: Exception) {
                // Ge inte upp huvudkraschen p.g.a. ett loggningsfel.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
