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
    // Toppfliksraden är borta – inställningar och felsökning öppnas i stället
    // som egna helskärmsvyer (kugghjulet i nedre listen respektive knappen
    // längst ner i inställningarna).
    var screen by remember { mutableStateOf(AppScreen.RADAR) }
    var fullscreen by remember { mutableStateOf(false) }
    var expandedPanel by remember { mutableStateOf(ExpandedPanel.NONE) }

    // Om anslutningen tappas, hoppa tillbaka till normalvy så användaren
    // ser felmeddelandet istället för en tom skärm.
    LaunchedEffect(appState) {
        if (appState !is RadarAppState.Streaming) fullscreen = false
    }

    // Bakåtknappen tar dig stegvis tillbaka: helskärm -> normalvy ->
    // flik 0 -> startvyn (koppla radar/emulator). Först därifrån får
    // systemet stänga appen, vilket gör en "Avsluta"-knapp i toppen onödig.
    androidx.activity.compose.BackHandler(
        enabled = fullscreen || screen != AppScreen.RADAR || appState !is RadarAppState.Disconnected
    ) {
        when {
            screen == AppScreen.DEBUG -> screen = AppScreen.SETTINGS
            screen != AppScreen.RADAR -> screen = AppScreen.RADAR
            fullscreen -> fullscreen = false
            else -> viewModel.reset()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Om felsökningsläget stängs av medan felsökningsvyn är öppen
            // stängs den automatiskt.
            LaunchedEffect(settings.debugMode) {
                if (!settings.debugMode && screen == AppScreen.DEBUG) screen = AppScreen.SETTINGS
            }

            if (screen == AppScreen.SETTINGS) {
                OverlayHeader("Inställningar") { screen = AppScreen.RADAR }
                SettingsScreen(
                    settings = settings,
                    onChange = { transform -> viewModel.updateSettings(transform) },
                    onResetSettings = { viewModel.resetSettings() },
                    onOpenDebug = if (settings.debugMode) ({ screen = AppScreen.DEBUG }) else null,
                    modifier = Modifier.weight(1f)
                )
                return@Column
            }

            if (screen == AppScreen.DEBUG && settings.debugMode) {
                OverlayHeader("Felsökning") { screen = AppScreen.SETTINGS }
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
                        Button(
                            onClick = { viewModel.connect(ssid, password) },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text("Anslut till radar")
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.connectEmulator() },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text("Emulatorläge")
                        }
                        Text(
                            "Demoläge – ingen radar behövs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { screen = AppScreen.SETTINGS }) {
                            Text("Inställningar")
                        }
                        Spacer(Modifier.height(24.dp))
                        // Avsluta hör hemma här på startvyn – tillbaka-knappen
                        // räcker för att ta sig hit från radarläget, så toppen
                        // behöver ingen permanent stängningsknapp.
                        TextButton(onClick = onExit) {
                            Text("Avsluta appen", color = MaterialTheme.colorScheme.error)
                        }
                        if (state is RadarAppState.Error) {
                            Spacer(Modifier.height(12.dp))
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    screen = if (settings.debugMode) AppScreen.DEBUG else AppScreen.SETTINGS
                                }
                            ) {
                                Text(
                                    if (settings.debugMode) "Visa felsökningslogg"
                                    else "Slå på felsökningsläge i Inställningar"
                                )
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
                                    .padding(start = 8.dp, end = 8.dp, top = 62.dp),
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
                                // Mållistan visas permanent bara om användaren
                                // valt det – annars poppar den upp enbart vid
                                // larm (och även det går att stänga av).
                                val showList = settings.showTargetList ||
                                    (settings.showAlarmPopup && alarmActive)
                                if (showList) {
                                    TargetListPanel(
                                        targets = targets,
                                        cpaLimitMeters = settings.cpaLimitMeters,
                                        tcpaLimitSeconds = settings.tcpaLimitSeconds,
                                        alarmMode = !settings.showTargetList && alarmActive
                                    )
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
                            onOpenSettings = { fullscreen = false; screen = AppScreen.SETTINGS },
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

private enum class AppScreen { RADAR, SETTINGS, DEBUG }

/** Enkel rubrikrad med tillbaka-knapp för de vyer som ersatt flikarna. */
@Composable
private fun OverlayHeader(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ Tillbaka") }
            Spacer(Modifier.width(4.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
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
