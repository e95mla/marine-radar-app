package com.example.marineradar

import android.Manifest
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.marineradar.network.RadarPortScanner
import com.example.marineradar.radar.RadarAppState
import com.example.marineradar.radar.RadarViewModel
import com.example.marineradar.ui.DebugScreen
import com.example.marineradar.ui.PpiView

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* resultat hanteras via appens state, ingen extra åtgärd behövs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(perms.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RadarScreen(viewModel)
            }
        }
    }
}

@Composable
fun RadarScreen(viewModel: RadarViewModel) {
    val appState by viewModel.appState.collectAsState()
    val spokeBuffer by viewModel.spokeBuffer.collectAsState()
    val network by viewModel.connectedNetwork.collectAsState()

    var ssid by remember { mutableStateOf(viewModel.settings.getSsid()) }
    var password by remember { mutableStateOf(viewModel.settings.getPassword()) }
    var tab by remember { mutableStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Radar") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Felsökning") })
            }

            if (tab == 1) {
                DebugScreen(
                    portScanner = network?.let { RadarPortScanner(it) },
                    modifier = Modifier.weight(1f)
                )
                return@Column
            }

        when (val state = appState) {
            is RadarAppState.Disconnected, is RadarAppState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Anslut till DRS4W", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text("WiFi-namn (SSID) på radarn") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Lösenord (lämna tomt om öppet nät)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.connect(ssid, password) }) {
                        Text("Anslut")
                    }
                    if (state is RadarAppState.Error) {
                        Spacer(Modifier.height(12.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { tab = 1 }) {
                            Text("Visa felsökningslogg")
                        }
                    }
                }
            }

            is RadarAppState.ConnectingWifi -> StatusScreen("Ansluter till WiFi …")
            is RadarAppState.DiscoveringRadar -> StatusScreen("Söker efter radar …")

            is RadarAppState.Streaming -> {
                Column(Modifier.fillMaxSize()) {
                    Text(
                        text = "Ansluten${state.model?.let { " – $it" } ?: ""}",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                    PpiView(
                        spokeBuffer = spokeBuffer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun StatusScreen(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text)
        }
    }
}
