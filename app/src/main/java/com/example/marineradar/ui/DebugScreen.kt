package com.example.marineradar.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.marineradar.debug.PacketLogEntry
import com.example.marineradar.debug.PacketLogger
import com.example.marineradar.network.RadarPortScanner
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DebugScreen(
    portScanner: RadarPortScanner?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val entries by PacketLogger.entries.collectAsState()
    var selected by remember { mutableStateOf<PacketLogEntry?>(null) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = portScanner != null && scanStatus == null,
                onClick = {
                    val scanner = portScanner ?: return@Button
                    scope.launch {
                        scanStatus = "Skannar portar …"
                        var found = 0
                        scanner.scan().collect { result ->
                            if (result.respondersFound > 0) found++
                        }
                        scanStatus = "Klar. Portar med svar: $found (se loggen nedan)"
                    }
                }
            ) { Text("Skanna portar") }

            OutlinedButton(onClick = { PacketLogger.clear() }) {
                Text("Rensa logg")
            }

            OutlinedButton(onClick = { shareLog(context) }) {
                Text("Dela logg")
            }
        }

        scanStatus?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Trafiklogg (${entries.size} paket) – tryck på en rad för hex-dump",
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

private fun shareLog(context: Context) {
    val text = PacketLogger.exportAsText()
    val file = File(context.cacheDir, "marine_radar_log.txt")
    file.writeText(text)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Dela trafiklogg"))
}
