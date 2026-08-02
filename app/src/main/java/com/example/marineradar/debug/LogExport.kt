package com.example.marineradar.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Slår ihop applogg + paketlogg (både sessionens och tidigare sparad på
 * disk) + ev. kraschrapport + grundläggande enhetsinfo till EN textfil.
 * Tanken är att du ska kunna dela/ladda ner en enda fil när du ber om
 * hjälp att felsöka, istället för att behöva plocka ihop flera separat.
 */
object LogExport {

    fun buildFullReport(): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        sb.appendLine("=".repeat(70))
        sb.appendLine("MARINE RADAR – SAMLAD FELSÖKNINGSRAPPORT")
        sb.appendLine("Genererad: $now")
        sb.appendLine("Enhet: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("=".repeat(70))
        sb.appendLine()

        sb.appendLine("-".repeat(70))
        sb.appendLine("KRASCHRAPPORT (senaste, om någon)")
        sb.appendLine("-".repeat(70))
        val crash = FileLogger.readLastCrash()
        sb.appendLine(crash ?: "(ingen krasch registrerad)")
        sb.appendLine()

        sb.appendLine("-".repeat(70))
        sb.appendLine("APPLOGG (denna + tidigare sessioner, sparad på disk)")
        sb.appendLine("-".repeat(70))
        sb.appendLine(FileLogger.readLog(200_000))
        sb.appendLine()

        sb.appendLine("-".repeat(70))
        sb.appendLine("PAKETLOGG – nuvarande session (${PacketLogger.entries.value.size} paket)")
        sb.appendLine("-".repeat(70))
        sb.appendLine(PacketLogger.exportAsText())
        sb.appendLine()

        sb.appendLine("-".repeat(70))
        sb.appendLine("PAKETLOGG – tidigare sparat på disk (kan överlappa ovan)")
        sb.appendLine("-".repeat(70))
        sb.appendLine(PacketLogger.readPersistedLog(200_000))

        return sb.toString()
    }

    /** Skriver rapporten till en fil och öppnar delningsdialogen (fungerar även som "ladda ner": välj t.ex. Filer/Drive i delningslistan). */
    fun shareFullReport(context: Context) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(context.cacheDir, "marine_radar_rapport_$timestamp.txt")
        file.writeText(buildFullReport())

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Dela/spara samlad felsökningsrapport")
        )
    }
}
