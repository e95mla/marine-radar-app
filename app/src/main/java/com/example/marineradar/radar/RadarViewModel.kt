package com.example.marineradar.radar

import android.app.Application
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.marineradar.debug.FileLogger
import com.example.marineradar.location.BoatLocationProvider
import com.example.marineradar.map.MapProviderType
import com.example.marineradar.network.FurunoRadarEmulator
import com.example.marineradar.network.RadarCommandClient
import com.example.marineradar.network.RadarControls
import com.example.marineradar.network.RadarInfo
import com.example.marineradar.network.RadarUdpClient
import com.example.marineradar.network.RadarWifiManager
import com.example.marineradar.network.WifiConnectionState
import com.example.marineradar.settings.RadarSettings
import com.example.marineradar.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.SocketTimeoutException

private const val TAG = "RadarViewModel"

/** Modellnamnet som [FurunoRadarEmulator] rapporterar. */
private const val EMULATOR_MODEL = "DRS4W-EMU"

sealed class RadarAppState {
    data object Disconnected : RadarAppState()
    data object ConnectingWifi : RadarAppState()
    data object DiscoveringRadar : RadarAppState()
    data class Streaming(val model: String?) : RadarAppState()
    data class Error(val message: String) : RadarAppState()
}

/**
 * All nätverkskod körs i coroutines som prenumereras på via [catch] innan
 * [collect] – det är avsiktligt och viktigt: utan det kraschar hela appen
 * så fort t.ex. discovery-frågan får timeout, eftersom en ofångad
 * coroutine-exception annars dödar processen. Nu visas ett felmeddelande
 * i UI:t istället, och detaljerna hamnar i filloggen under Felsökning.
 */
class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val wifiManager = RadarWifiManager(application)
    val settings = SettingsStore(application)

    /**
     * Alla användarinställningar i ett objekt, laddade från disk vid start
     * och sparade vid varje ändring – se [RadarSettings]/[SettingsStore].
     */
    private val _uiSettings = MutableStateFlow(settings.load())
    val uiSettings: StateFlow<RadarSettings> = _uiSettings.asStateFlow()

    private val alarmPlayer = AlarmPlayer(application)

    /** Mål-id som redan larmat, så att larmet bara ljuder när ett NYTT mål triggar. */
    private val alarmedIds = mutableSetOf<Int>()

    private val _alarmActive = MutableStateFlow(false)
    val alarmActive: StateFlow<Boolean> = _alarmActive.asStateFlow()

    /**
     * Ändrar en eller flera inställningar och sparar dem direkt. Alla
     * sidoeffekter (detektionströskel, av/på för målspårning) hanteras här
     * så att vyerna bara behöver beskriva ÖNSKAT läge.
     */
    fun updateSettings(transform: (RadarSettings) -> RadarSettings) {
        val updated = transform(_uiSettings.value)
        _uiSettings.value = updated
        settings.save(updated)
        tracker.threshold = updated.detectThreshold
        if (!updated.targetTrackingEnabled) {
            tracker.reset()
            _targets.value = emptyList()
            _alarmActive.value = false
            alarmedIds.clear()
        }
        // Spegelflöden som kartvyerna redan lyssnar på.
        _mapProvider.value = runCatching { MapProviderType.valueOf(updated.mapProviderName) }
            .getOrDefault(MapProviderType.OPENSTREETMAP)
        _radarOpacity.value = updated.radarOpacity
        _mapDarkStyle.value = updated.mapDarkStyle
    }

    /** Återställer alla inställningar till fabriksläge. */
    fun resetSettings() {
        settings.clear()
        updateSettings { RadarSettings() }
    }

    private val _appState = MutableStateFlow<RadarAppState>(RadarAppState.Disconnected)
    val appState: StateFlow<RadarAppState> = _appState.asStateFlow()

    private val _ppiRenderer = MutableStateFlow<PpiRenderer?>(null)
    val ppiRenderer: StateFlow<PpiRenderer?> = _ppiRenderer.asStateFlow()

    private val _connectedNetwork = MutableStateFlow<Network?>(null)
    val connectedNetwork: StateFlow<Network?> = _connectedNetwork.asStateFlow()

    private val _radarControls = MutableStateFlow(RadarControls())
    val radarControls: StateFlow<RadarControls> = _radarControls.asStateFlow()

    private var emulator: FurunoRadarEmulator? = null
    private val _isEmulatorMode = MutableStateFlow(false)
    val isEmulatorMode: StateFlow<Boolean> = _isEmulatorMode.asStateFlow()

    private var commandClient: RadarCommandClient? = null
    private var spokeDecoder: FurunoSpokeDecoder? = null

    // -------------------------------------------------------------------
    // Karta + positionering
    // -------------------------------------------------------------------
    private var locationProvider: BoatLocationProvider? = null

    private val _showMapOverlay = MutableStateFlow(false)
    val showMapOverlay: StateFlow<Boolean> = _showMapOverlay.asStateFlow()

    private val _mapProvider = MutableStateFlow(
        runCatching { MapProviderType.valueOf(settings.load().mapProviderName) }
            .getOrDefault(MapProviderType.OPENSTREETMAP)
    )
    val mapProvider: StateFlow<MapProviderType> = _mapProvider.asStateFlow()

    private val _radarOpacity = MutableStateFlow(settings.load().radarOpacity)
    val radarOpacity: StateFlow<Float> = _radarOpacity.asStateFlow()

    private val _mapDarkStyle = MutableStateFlow(settings.load().mapDarkStyle)
    val mapDarkStyle: StateFlow<Boolean> = _mapDarkStyle.asStateFlow()

    private val _boatLocation = MutableStateFlow<com.google.android.gms.maps.model.LatLng?>(null)
    val boatLocation: StateFlow<com.google.android.gms.maps.model.LatLng?> = _boatLocation.asStateFlow()

    private val _headingDegrees = MutableStateFlow(0f)
    val headingDegrees: StateFlow<Float> = _headingDegrees.asStateFlow()

    private val _speedKnots = MutableStateFlow<Float?>(null)
    val speedKnots: StateFlow<Float?> = _speedKnots.asStateFlow()

    private val _courseOverGround = MutableStateFlow<Float?>(null)
    val courseOverGround: StateFlow<Float?> = _courseOverGround.asStateFlow()

    // -------------------------------------------------------------------
    // Målspårning (MARPA-lite) – härleds ur radarbilden, se [TargetTracker]
    // -------------------------------------------------------------------
    private val tracker = TargetTracker().apply { threshold = settings.load().detectThreshold }

    private val _targets = MutableStateFlow<List<RadarTarget>>(emptyList())
    val targets: StateFlow<List<RadarTarget>> = _targets.asStateFlow()

    fun setTargetTrackingEnabled(enabled: Boolean) {
        updateSettings { it.copy(targetTrackingEnabled = enabled) }
    }

    /**
     * Startar telefonens GPS/kompass. Behövs både för kartöverlägget och
     * för navigationsraden (HDG/COG/SOG) – radarn själv skickar ingen
     * positions- eller kursinformation.
     */
    private fun ensureLocationProvider() {
        if (locationProvider != null) return
        FileLogger.log("INFO", "$TAG: startar GPS/kompass för kurs- och fartvisning")
        val provider = BoatLocationProvider(getApplication())
        locationProvider = provider
        provider.start()
        viewModelScope.launch { provider.location.collect { _boatLocation.value = it } }
        viewModelScope.launch { provider.headingDegrees.collect { _headingDegrees.value = it } }
        viewModelScope.launch { provider.speedKnots.collect { _speedKnots.value = it } }
        viewModelScope.launch { provider.courseOverGround.collect { _courseOverGround.value = it } }
    }

    fun setMapOverlayEnabled(enabled: Boolean) {
        _showMapOverlay.value = enabled
        if (enabled) ensureLocationProvider()
    }

    fun setMapProvider(provider: MapProviderType) {
        updateSettings { it.copy(mapProviderName = provider.name) }
    }

    fun setRadarOpacity(value: Float) {
        updateSettings { it.copy(radarOpacity = value.coerceIn(0f, 1f)) }
    }

    fun setMapDarkStyle(dark: Boolean) {
        updateSettings { it.copy(mapDarkStyle = dark) }
    }

    /**
     * Startar en lokal simulator som pratar exakt samma protokoll som en
     * riktig DRS4W, men över loopback (127.0.0.1) – för att kunna testa
     * hela appens pipeline (discovery → spoke-avkodning → PPI-rendering
     * → kommandokanal) utan att vara i närheten av den riktiga radarn.
     */
    fun connectEmulator() {
        if (_appState.value !is RadarAppState.Disconnected && _appState.value !is RadarAppState.Error) {
            FileLogger.log("WARN", "$TAG: connectEmulator() ignorerad, redan ansluten/ansluter")
            return
        }
        reset() // säkerställ att ev. gammal emulator/kommandoklient är helt nedstängd först
        FileLogger.log("INFO", "$TAG: Startar emulatorläge (simulerad radar, ingen riktig hårdvara)")
        _isEmulatorMode.value = true
        _appState.value = RadarAppState.DiscoveringRadar

        val em = FurunoRadarEmulator()
        emulator = em
        em.start(viewModelScope)

        // Ge emulatorn en liten stund att hinna binda sina lyssnarsocklar
        // innan vi börjar prata med den.
        viewModelScope.launch {
            delay(300)
            // I emulatorläget vet vi redan exakt var "radarn" finns
            // (127.0.0.1) – vi ska INTE köra discovery, eftersom det
            // annars broadcastar ut på det riktiga nätverket och letar
            // efter hårdvara som inte ska vara inblandad.
            val emulatorInfo = RadarInfo(
                ipAddress = InetAddress.getByName("127.0.0.1"),
                model = EMULATOR_MODEL,
                serialNumber = null,
                name = EMULATOR_MODEL
            )
            startRadarSession(RadarUdpClient(null, getApplication()), knownRadar = emulatorInfo)
        }
    }

    fun connect(ssid: String, password: String) {
        if (_appState.value !is RadarAppState.Disconnected && _appState.value !is RadarAppState.Error) {
            FileLogger.log("WARN", "$TAG: connect() ignorerad, redan ansluten/ansluter")
            return
        }
        reset()
        updateSettings { it.copy(ssid = ssid, password = password) }
        FileLogger.log("INFO", "$TAG: Ansluter till WiFi '$ssid'")
        _appState.value = RadarAppState.ConnectingWifi

        viewModelScope.launch {
            wifiManager.connect(ssid, password)
                .catch { e ->
                    FileLogger.log("ERROR", "$TAG: WiFi-anslutning misslyckades", e)
                    _appState.value = RadarAppState.Error(
                        "WiFi-fel: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
                .collect { state ->
                    when (state) {
                        is WifiConnectionState.Connected -> {
                            FileLogger.log("INFO", "$TAG: WiFi anslutet")
                            _appState.value = RadarAppState.DiscoveringRadar
                            _connectedNetwork.value = state.network
                            startRadarSession(RadarUdpClient(state.network, getApplication()))
                        }
                        is WifiConnectionState.Failed -> {
                            FileLogger.log("WARN", "$TAG: WiFi misslyckades: ${state.reason}")
                            _appState.value = RadarAppState.Error(state.reason)
                        }
                        else -> Unit
                    }
                }
        }
    }

    private fun startRadarSession(udpClient: RadarUdpClient, knownRadar: RadarInfo? = null) {
        if (knownRadar != null) {
            // Ingen discovery – vi hoppar direkt till strömning.
            FileLogger.log("INFO", "$TAG: Hoppar över discovery, känd radar=${knownRadar.model}")
            _appState.value = RadarAppState.Streaming(knownRadar.model)
            _ppiRenderer.value = PpiRenderer()
            tracker.reset()
            ensureLocationProvider()
            startSpokeListener(udpClient)
            startCommandChannel(knownRadar.ipAddress)
            return
        }
        viewModelScope.launch {
            udpClient.discover()
                .catch { e ->
                    val friendly = when (e) {
                        is SocketTimeoutException ->
                            "Ingen radar svarade på discovery-frågan inom tidsgränsen. " +
                                "Radarn kan svara på en annan port än väntat – kör " +
                                "Skanna portar under Felsökning för att undersöka."
                        else -> "Fel vid radar-discovery: ${e.message ?: e.javaClass.simpleName}"
                    }
                    FileLogger.log("ERROR", "$TAG: discover() misslyckades", e)
                    _appState.value = RadarAppState.Error(friendly)
                }
                .collect { info ->
                    FileLogger.log("INFO", "$TAG: Radar hittad, modell=${info.model}")
                    _appState.value = RadarAppState.Streaming(info.model)
                    _ppiRenderer.value = PpiRenderer()
                    tracker.reset()
                    ensureLocationProvider()
                    startSpokeListener(udpClient)
                    startCommandChannel(info.ipAddress)
                }
        }
    }

    private fun startSpokeListener(udpClient: RadarUdpClient) {
        val decoder = FurunoSpokeDecoder() // en instans per session – encoding 2/3 är delta-kodat
        spokeDecoder = decoder
        var packetCount = 0L
        var spokeCount = 0L
        var errorCount = 0L
        var lastSpokeAtMs = 0L
        val sessionStart = System.currentTimeMillis()

        // Hälsokontroll: skriver var 5:e sekund vad som faktiskt kommer in,
        // så att loggen visar om problemet är "inga paket alls" (nätverk/
        // multicast) eller "paket kommer men avkodas inte" (protokoll).
        viewModelScope.launch {
            while (_appState.value is RadarAppState.Streaming) {
                delay(5_000)
                val secs = (System.currentTimeMillis() - sessionStart) / 1000
                val sinceSpoke =
                    if (lastSpokeAtMs == 0L) "aldrig" else "${(System.currentTimeMillis() - lastSpokeAtMs) / 1000}s sedan"
                FileLogger.log(
                    if (spokeCount == 0L) "WARN" else "INFO",
                    "$TAG: sessionsstatus efter ${secs}s – paket=$packetCount, " +
                        "avkodade spokes=$spokeCount, avkodningsfel=$errorCount, " +
                        "senaste spoke=$sinceSpoke, kommandokanal=${_radarControls.value.connected}, " +
                        "sändning=${_radarControls.value.powerTransmit}" +
                        when {
                        !_radarControls.value.powerTransmit ->
                            " → RADARN ÄR I STANDBY: ingen spoke-data ska komma. Tryck STBY för att begära TX; " +
                                "loggen ska då visa TX \$S69 och ett bekräftat \$N69-läge"
                            packetCount == 0L ->
                                " → INGEN UDP-data alls: kontrollera att telefonen sitter på radarns WiFi " +
                                    "och att MulticastLock togs (se RadarUdpClient-raderna ovan)"
                            spokeCount == 0L ->
                                " → paket kommer in men inga spokes avkodas: fel format/encoding, se hex-dump i paketloggen"
                            else -> ""
                        }
                )
            }
        }

        viewModelScope.launch {
            udpClient.listenForSpokes()
                .catch { e ->
                    FileLogger.log("ERROR", "$TAG: Spoke-lyssnare kraschade", e)
                    _appState.value = RadarAppState.Error(
                        "Tappade anslutningen till spoke-strömmen: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
                .collect { raw ->
                    packetCount++
                    try {
                        val spokes = decoder.decodeFrame(raw)
                        if (spokes.isEmpty()) {
                            if (packetCount <= 20 || packetCount % 200 == 0L) {
                                FileLogger.log(
                                    "WARN",
                                    "$TAG: paket #$packetCount (${raw.size} B) gav 0 spokes – " +
                                        raw.take(24).joinToString(" ") { "%02X".format(it) }
                                )
                            }
                        } else if (spokeCount == 0L) {
                            FileLogger.log(
                                "INFO",
                                "$TAG: FÖRSTA avkodade spokes: ${spokes.size} st, " +
                                    "vinkel=${spokes.first().angle}, " +
                                    "celler=${spokes.first().intensities.size}"
                            )
                        }
                        spokeCount += spokes.size
                        if (spokes.isNotEmpty()) lastSpokeAtMs = System.currentTimeMillis()
                        val renderer = _ppiRenderer.value
                        if (renderer == null) {
                            FileLogger.log("WARN", "$TAG: ingen PPI-renderare aktiv – spokes kastas")
                            return@collect
                        }
                        for (spoke in spokes) {
                            renderer.drawSpoke(spoke.angle, spoke.intensities)
                            if (_uiSettings.value.targetTrackingEnabled) {
                                tracker.setRange(_radarControls.value.rangeMeters)
                                tracker.onSpoke(spoke.angle, spoke.intensities)?.let { list ->
                                    _targets.value = list
                                    evaluateAlarms(list)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        errorCount++
                        // Ett enskilt trasigt/oväntat paket ska aldrig krascha
                        // appen – logga och hoppa bara över det.
                        FileLogger.log("WARN", "$TAG: Kunde inte avkoda spoke-paket (${raw.size} B)", e)
                    }
                }
        }
    }

    /** Startar login + kommandokanal mot radarn (Range/Gain/Sea/Rain/Standby-Transmit). */
    private fun startCommandChannel(radarIp: java.net.InetAddress) {
        // Binder kommandokanalen till radarns WiFi-nätverk – annars går TCP ut
        // via mobildata (default-nätverket) och timeoutar.
        val client = RadarCommandClient(_connectedNetwork.value)
        commandClient = client
        viewModelScope.launch {
            client.controls.collect { _radarControls.value = it }
        }
        viewModelScope.launch {
            client.connectAndListen(radarIp)
        }
        if (_uiSettings.value.autoTransmitOnConnect) {
            viewModelScope.launch {
                // Vänta tills kanalen faktiskt är uppe innan TX begärs.
                repeat(30) {
                    if (_radarControls.value.connected) return@repeat
                    delay(500)
                }
                if (_radarControls.value.connected && !_radarControls.value.powerTransmit) {
                    FileLogger.log("INFO", "$TAG: auto-transmit påslaget – begär TRANSMIT")
                    setPower(true)
                }
            }
        }
    }

    /**
     * Kollar vaktzon och CPA-gränser mot de senaste målen och ljuder larm
     * när ett NYTT mål bryter mot en gräns (kanttriggat, så att larmet inte
     * tjuter oavbrutet för samma mål).
     */
    private fun evaluateAlarms(list: List<RadarTarget>) {
        val s = _uiSettings.value
        val triggering = list.filter { t ->
            val cpaHit = s.cpaAlarmEnabled && t.isDangerous(s.cpaLimitMeters, s.tcpaLimitSeconds)
            val guardHit = s.guardEnabled && inGuardZone(t, s)
            cpaHit || guardHit
        }
        val ids = triggering.map { it.id }.toSet()
        val fresh = ids - alarmedIds
        alarmedIds.retainAll(ids)
        alarmedIds.addAll(ids)
        _alarmActive.value = ids.isNotEmpty()
        if (fresh.isNotEmpty() && (s.alarmSound || s.alarmVibrate)) {
            FileLogger.log("WARN", "$TAG: LARM – mål ${fresh.joinToString()} bryter vaktzon/CPA-gräns")
            alarmPlayer.alert(s.alarmSound, s.alarmVibrate)
        }
    }

    private fun inGuardZone(t: RadarTarget, s: RadarSettings): Boolean {
        if (t.rangeMeters < s.guardInnerMeters || t.rangeMeters > s.guardOuterMeters) return false
        if (s.guardWidthDeg >= 359.5f) return true
        val rel = ((t.bearingDegrees - s.guardStartDeg) % 360f + 360f) % 360f
        return rel <= s.guardWidthDeg
    }

    fun setPower(transmit: Boolean) = launchCommand {
        FileLogger.log(
            "INFO",
            "$TAG: användaren begär effektläge ${if (transmit) "TRANSMIT" else "STANDBY"}; " +
                "klient=${if (commandClient == null) "SAKNAS" else "redo"}"
        )
        // Delta-avkodningen (encoding 2/3) refererar föregående spoke – när
        // radarn går till standby måste tillståndet nollställas, annars blir
        // första varvet efter uppvaknandet brus.
        if (!transmit) {
            spokeDecoder?.reset()
            tracker.reset()
            _targets.value = emptyList()
        }
        commandClient?.setPower(transmit)
    }
    fun stepRange(up: Boolean) = launchCommand { commandClient?.stepRange(up) }
    fun setGain(auto: Boolean, value: Int) = launchCommand { commandClient?.setGain(auto, value) }
    fun setSea(auto: Boolean, value: Int) = launchCommand { commandClient?.setSea(auto, value) }
    fun setRain(auto: Boolean, value: Int) = launchCommand { commandClient?.setRain(auto, value) }

    /**
     * Kör ett kommando på bakgrundstråd. VIKTIGT: Compose-callbacks (t.ex.
     * Slider.onValueChange) körs på huvudtråden, och att skriva till en
     * socket direkt därifrån kastar NetworkOnMainThreadException – som
     * (förrädiskt nog) har `message == null`, vilket är precis vad som
     * loggades som "kunde inte skicka kommando: null" tidigare.
     */
    private fun launchCommand(block: () -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { block() }
    }

    fun reset() {
        _appState.value = RadarAppState.Disconnected
        _connectedNetwork.value = null
        _ppiRenderer.value = null
        _radarControls.value = RadarControls()
        commandClient?.close()
        commandClient = null
        emulator?.stop()
        emulator = null
        _isEmulatorMode.value = false
        locationProvider?.stop()
        locationProvider = null
        _showMapOverlay.value = false
        _boatLocation.value = null
        _speedKnots.value = null
        _courseOverGround.value = null
        tracker.reset()
        _targets.value = emptyList()
        _alarmActive.value = false
        alarmedIds.clear()
    }

    override fun onCleared() {
        super.onCleared()
        commandClient?.close()
        emulator?.stop()
        locationProvider?.stop()
        alarmPlayer.release()
    }
}
