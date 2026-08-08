package com.example.marineradar

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.marineradar.network.RadarPassiveScanner
import com.example.marineradar.network.RadarPortScanner
import com.example.marineradar.radar.RadarAppState
import com.example.marineradar.radar.RadarViewModel
import com.example.marineradar.ui.DebugScreen
import com.example.marineradar.ui.ExpandedPanel
import com.example.marineradar.ui.ExpandableBottomBar
import com.example.marineradar.ui.PpiView
import com.example.marineradar.ui.RadarDataBar
import com.example.marineradar.ui.TargetListPanel
import com.example.marineradar.ui.MapStylePicker
import com.example.marineradar.ui.RadarMapContainer
import com.example.marineradar.ui.SettingsScreen
import com.example.marineradar.ui.SquareIconToggle

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
                RadarScreen(viewModel, onExit = { finishAndRemoveTask() })
            }
        }
    }
}

@Composable
fun RadarScreen(viewModel: RadarViewModel, onExit: () -> Unit) {
    val appState by viewModel.appState.collectAsState()
    val ppiRenderer by viewModel.ppiRenderer.collectAsState()
    val network by viewModel.connectedNetwork.collectAsState()
    val radarControls by viewModel.radarControls.collectAsState()
    val isEmulator by viewModel.isEmulatorMode.collectAsState()
    val showMapOverlay by viewModel.showMapOverlay.collectAsState()
    val mapProvider by viewModel.mapProvider.collectAsState()
    val mapOpacity by viewModel.radarOpacity.collectAsState()
    val mapStyle by viewModel.mapStyle.collectAsState()
    val boatLocation by viewModel.boatLocation.collectAsState()
    val headingDegrees by viewModel.headingDegrees.collectAsState()
    val speedKnots by viewModel.speedKnots.collectAsState()
    val courseOverGround by viewModel.courseOverGround.collectAsState()
    val targets by viewModel.targets.collectAsState()
    val settings by viewModel.uiSettings.collectAsState()
    val alarmActive by viewModel.alarmActive.collectAsState()

    // Håller skärmen tänd medan radarn strömmar, om användaren valt det.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(settings.keepScreenOn, appState) {
        view.keepScreenOn = settings.keepScreenOn && appState is RadarAppState.Streaming
        onDispose { view.keepScreenOn = false }
    }

    var ssid by remember { mutableStateOf(settings.ssid) }
    var password by remember { mutableStateOf(settings.password) }
    var tab by remember { mutableStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var expandedPanel by remember { mutableStateOf(ExpandedPanel.NONE) }

    // Om anslutningen tappas, hoppa tillbaka till normalvy så användaren
    // ser felmeddelandet istället för en tom skärm.
    LaunchedEffect(appState) {
        if (appState !is RadarAppState.Streaming) fullscreen = false
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Radar") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Inställningar") })
                        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Felsökning") })
                    }
                    TextButton(onClick = onExit) {
                        Text("✕ Avsluta", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (tab == 1 && !fullscreen) {
                SettingsScreen(
                    settings = settings,
                    onChange = { transform -> viewModel.updateSettings(transform) },
                    onResetSettings = { viewModel.resetSettings() },
                    modifier = Modifier.weight(1f)
                )
                return@Column
            }

            if (tab == 2 && !fullscreen) {
                DebugScreen(
                    portScanner = network?.let { RadarPortScanner(it) },
                    passiveScanner = network?.let { RadarPassiveScanner(it) },
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
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.connectEmulator() }) {
                            Text("🧪 Testa med emulator (ingen radar behövs)")
                        }
                        if (state is RadarAppState.Error) {
                            Spacer(Modifier.height(12.dp))
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { tab = 2 }) {
                                Text("Visa felsökningslogg")
                            }
                        }
                    }
                }

                is RadarAppState.ConnectingWifi -> StatusScreen("Ansluter till WiFi …")
                is RadarAppState.DiscoveringRadar -> StatusScreen("Söker efter radar …")

                is RadarAppState.Streaming -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Kärninnehållet fyller alltid hela ytan, oavsett
                        // helskärm eller ej – karta eller klassisk PPI-vy.
                        if (showMapOverlay) {
                            RadarMapContainer(
                                provider = mapProvider,
                                renderer = ppiRenderer,
                                boatLocation = boatLocation,
                                headingDegrees = headingDegrees,
                                rangeMeters = radarControls.rangeMeters,
                                opacity = mapOpacity,
                                style = mapStyle,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            PpiView(
                                renderer = ppiRenderer,
                                settings = settings,
                                targets = targets,
                                rangeMeters = radarControls.rangeMeters,
                                headingDegrees = headingDegrees,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (!fullscreen) {
                            // Diskret statusindikator: grön prick = riktig
                            // radar, blå prick = emulator. Ersätter den
                            // tidigare skrymmande textraden.
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                                color = Color.Black.copy(alpha = 0.55f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                color = if (isEmulator) Color(0xFF4A90E2) else Color(0xFF4CD964),
                                                shape = androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        state.model ?: "Radar",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        if (!fullscreen) {
                            // Navigationsdata (HDG/COG/SOG/räckvidd/mål) +
                            // mållista – radarn skickar ingen navdata själv,
                            // så HDG/COG/SOG kommer från telefonens sensorer.
                            Column(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 8.dp, top = 44.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (settings.showDataBar) {
                                    RadarDataBar(
                                        headingDegrees = headingDegrees,
                                        courseOverGround = courseOverGround,
                                        speedKnots = speedKnots,
                                        rangeMeters = radarControls.rangeMeters,
                                        targetCount = targets.size,
                                        dangerCount = targets.count {
                                            it.isDangerous(settings.cpaLimitMeters, settings.tcpaLimitSeconds)
                                        },
                                        northUp = settings.northUp,
                                        alarmActive = alarmActive
                                    )
                                }
                                if (settings.showTargetList) {
                                    TargetListPanel(targets = targets)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (showMapOverlay) {
                                // Standardikonen för kartlager – byter
                                // kartfilter (satellit/terräng/sjökort …)
                                // direkt i bilden, även i helskärm.
                                MapStylePicker(
                                    style = mapStyle,
                                    onStyleChange = { viewModel.setMapStyle(it) }
                                )
                            }
                            if (!fullscreen) {
                                SquareIconToggle(
                                    icon = "🗺️",
                                    selected = showMapOverlay,
                                    onClick = {
                                        val newValue = !showMapOverlay
                                        viewModel.setMapOverlayEnabled(newValue)
                                        if (!newValue && expandedPanel == ExpandedPanel.MAP) {
                                            expandedPanel = ExpandedPanel.NONE
                                        }
                                    }
                                )
                                SquareIconToggle(
                                    icon = "⏏",
                                    selected = false,
                                    onClick = { viewModel.reset() }
                                )
                            }
                            SquareIconToggle(
                                icon = if (fullscreen) "⤡" else "⤢",
                                selected = false,
                                onClick = { fullscreen = !fullscreen }
                            )
                        }

                        // Räckvidd + zoom + expanderbara paneler – ALLTID
                        // synligt längst ner, även i helskärm.
                        ExpandableBottomBar(
                            showMapButton = showMapOverlay,
                            expandedPanel = expandedPanel,
                            onExpandedPanelChange = { expandedPanel = it },
                            rangeMeters = radarControls.rangeMeters,
                            rangePending = radarControls.rangePending,
                            onRangeStep = { viewModel.stepRange(it) },
                            onOpenSettings = { fullscreen = false; tab = 1 },
                            radarControls = radarControls,
                            onPowerToggle = { viewModel.setPower(it) },
                            onGainChange = { auto, value -> viewModel.setGain(auto, value) },
                            onSeaChange = { auto, value -> viewModel.setSea(auto, value) },
                            onRainChange = { auto, value -> viewModel.setRain(auto, value) },
                            mapOpacity = mapOpacity,
                            onMapOpacityChange = { viewModel.setRadarOpacity(it) },
                            modifier = Modifier.align(Alignment.BottomCenter)
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
