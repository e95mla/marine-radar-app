package com.example.marineradar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.marineradar.debug.FileLogger
import com.example.marineradar.debug.LogExport
import com.example.marineradar.debug.PacketLogEntry
import com.example.marineradar.debug.PacketLogger
import com.example.marineradar.network.RadarPassiveScanner
import com.example.marineradar.network.RadarPortScanner
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DebugScreen(
    portScanner: RadarPortScanner?,
    passiveScanner: RadarPassiveScanner?,
    modifier: Modifier = Modifier
) {
    var subTab by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val saveFile = rememberSaveToFile()

    Column(modifier = modifier.fillMaxSize()) {
        Button(
            onClick = { saveFile(LogExport.suggestedFileName(), LogExport.buildFullReport()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("\u2b07\ufe0f Ladda ner ALLT (applogg + paket + krasch) till en fil")
        }
        OutlinedButton(
            onClick = { LogExport.shareFullReport(context) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Text("\ud83d\udce4 Dela istället till en annan app")
        }

        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Paket") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Applogg") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Krasch") })
        }

        when (subTab) {
            0 -> PacketLogTab(portScanner, passiveScanner, Modifier.weight(1f))
            1 -> AppLogTab(Modifier.weight(1f))
            2 -> CrashLogTab(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PacketLogTab(portScanner: RadarPortScanner?, passiveScanner: RadarPassiveScanner?, modifier: Modifier) {
    val context = LocalContext.current
    val entries by PacketLogger.entries.collectAsState()
    var selected by remember { mutableStateOf<PacketLogEntry?>(null) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveFile = rememberSaveToFile()

    Column(modifier = modifier.padding(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = portScanner != null && scanStatus == null,
                onClick = {
                    val scanner = portScanner ?: return@Button
                    scope.launch {
                        scanStatus = "Skickar \$N96-fråga till kandidatportar …"
                        try {
                            var found = 0
                            scanner.scan().collect { result ->
                                if (result.respondersFound > 0) found++
                            }
                            scanStatus = "Aktiv skanning klar. Portar med svar: $found (se loggen nedan)"
                        } catch (e: Exception) {
                            FileLogger.log("ERROR", "Portskanning misslyckades", e)
                            scanStatus = "Skanning avbröts: ${e.message}"
                        }
                    }
                }
            ) { Text("Aktiv skanning") }

            Button(
                enabled = passiveScanner != null && scanStatus == null,
                onClick = {
                    val scanner = passiveScanner ?: return@Button
                    scope.launch {
                        scanStatus = "Lyssnar passivt i 20s på brett portspann + multicast …"
                        try {
                            scanner.passiveScan(20_000).collect { summary ->
                                scanStatus = "Passiv skanning klar. Lyssnade på ${summary.portsListened} " +
                                    "portar + ${summary.multicastGroupsJoined} multicast-grupper, " +
                                    "hörde ${summary.packetsHeard} paket (se loggen nedan)"
                            }
                        } catch (e: Exception) {
                            FileLogger.log("ERROR", "Passiv skanning misslyckades", e)
                            scanStatus = "Skanning avbröts: ${e.message}"
                        }
                    }
                }
            ) { Text("Passiv skanning (20s)") }

            OutlinedButton(onClick = { PacketLogger.clear() }) {
                Text("Rensa")
            }

            OutlinedButton(onClick = {
                saveFile("marine_radar_packets.txt", PacketLogger.exportAsText())
            }) {
                Text("Ladda ner")
            }

            OutlinedButton(onClick = { sharePacketLog(context) }) {
                Text("Dela")
            }
        }

        scanStatus?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Trafiklogg (${entries.size} paket, sparas även till disk) – tryck på en rad för hex-dump",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(4.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(entries.reversed()) { entry ->
                ListItem(
                    headlineContent = {
                        Text(entry.summary, fontFamily = FontFamily.Monospace)
                    },
                    modifier = Modifier.clickable { selected = entry }
                )
                HorizontalDivider()
            }
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Stäng") }
            },
            title = { Text(entry.summary) },
            text = {
                Column {
                    Text("Hex:", style = MaterialTheme.typography.labelMedium)
                    Text(entry.hexDump, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("ASCII:", style = MaterialTheme.typography.labelMedium)
                    Text(entry.asciiPreview, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }
}

@Composable
private fun AppLogTab(modifier: Modifier) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(FileLogger.readLog()) }
    val saveFile = rememberSaveToFile()

    Column(modifier = modifier.padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { text = FileLogger.readLog() }) { Text("Uppdatera") }
            OutlinedButton(onClick = { FileLogger.clearLog(); text = FileLogger.readLog() }) { Text("Rensa") }
            OutlinedButton(onClick = { saveFile("app_log.txt", text) }) { Text("Ladda ner") }
            OutlinedButton(onClick = { shareText(context, text, "app_log.txt", "Dela applogg") }) { Text("Dela") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Sparas löpande på disk (${FileLogger.logFilePath()}), finns kvar mellan sessioner.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun CrashLogTab(modifier: Modifier) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(FileLogger.readLastCrash()) }
    val saveFile = rememberSaveToFile()

    Column(modifier = modifier.padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { text = FileLogger.readLastCrash() }) { Text("Uppdatera") }
            if (text != null) {
                OutlinedButton(onClick = { saveFile("crash_log.txt", text ?: "") }) { Text("Ladda ner") }
                OutlinedButton(onClick = { shareText(context, text ?: "", "crash_log.txt", "Dela kraschrapport") }) {
                    Text("Dela")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (text == null) {
            Text("Ingen krasch registrerad sedan appen senast installerades. Bra!")
        } else {
            Text(
                text ?: "",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}


/**
 * Ger en `save(filnamn, innehåll)`-funktion som öppnar Androids egen
 * "Spara som"-dialog (Storage Access Framework) så att filen kan sparas
 * direkt i t.ex. Nedladdningar – utan att behöva skicka den vidare till
 * en annan app via delningsmenyn.
 */
@Composable
private fun rememberSaveToFile(): (String, String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val content = pending?.second
        pending = null
        if (uri == null || content == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray())
            }
            Toast.makeText(context, "Filen sparad", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FileLogger.log("ERROR", "Kunde inte spara loggfil", e)
            Toast.makeText(context, "Kunde inte spara: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    return { filename, content ->
        pending = filename to content
        launcher.launch(filename)
    }
}

private fun sharePacketLog(context: Context) {
    shareText(context, PacketLogger.exportAsText(), "marine_radar_packets.txt", "Dela trafiklogg")
}

private fun shareText(context: Context, text: String, filename: String, title: String) {
    val file = File(context.cacheDir, filename)
    file.writeText(text)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
}
