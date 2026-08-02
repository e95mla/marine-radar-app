package com.example.marineradar.radar

import android.app.Application
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.marineradar.debug.FileLogger
import com.example.marineradar.network.FurunoRadarEmulator
import com.example.marineradar.network.RadarUdpClient
import com.example.marineradar.network.RadarWifiManager
import com.example.marineradar.network.WifiConnectionState
import com.example.marineradar.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException

const val ANGLE_STEPS = 2048 // upplösning på PPI-bilden (antal spokes per varv)
private const val TAG = "RadarViewModel"

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
 * så fort t.ex. discovery-frågan får timeout (radarn svarar inte inom
 * tidsgränsen), eftersom en ofångad coroutine-exception annars dödar
 * processen. Nu visas ett felmeddelande i UI:t istället, och detaljerna
 * hamnar i filloggen under Felsökning.
 */
class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val wifiManager = RadarWifiManager(application)
    val settings = SettingsStore(application)

    private val _appState = MutableStateFlow<RadarAppState>(RadarAppState.Disconnected)
    val appState: StateFlow<RadarAppState> = _appState.asStateFlow()

    // Cirkulär buffer: en rad intensiteter per vinkelsteg. UI:t läser
    // detta direkt varje frame för att rita PPI-bilden.
    private val _spokeBuffer = MutableStateFlow(Array(ANGLE_STEPS) { ByteArray(0) })
    val spokeBuffer: StateFlow<Array<ByteArray>> = _spokeBuffer.asStateFlow()

    private val _connectedNetwork = MutableStateFlow<Network?>(null)
    val connectedNetwork: StateFlow<Network?> = _connectedNetwork.asStateFlow()

    private var emulator: FurunoRadarEmulator? = null
    private val _isEmulatorMode = MutableStateFlow(false)
    val isEmulatorMode: StateFlow<Boolean> = _isEmulatorMode.asStateFlow()

    /**
     * Startar en lokal simulator som pratar exakt samma protokoll som en
     * riktig DRS4W, men över loopback (127.0.0.1) – för att kunna testa
     * hela appens pipeline (discovery → spoke-avkodning → PPI-rendering)
     * utan att vara i närheten av den riktiga radarn.
     */
    fun connectEmulator() {
        FileLogger.log("INFO", "$TAG: Startar emulatorläge (simulerad radar, ingen riktig hårdvara)")
        _isEmulatorMode.value = true
        _appState.value = RadarAppState.DiscoveringRadar

        val em = FurunoRadarEmulator()
        emulator = em
        em.start(viewModelScope)

        // Ge emulatorn en liten stund att hinna binda sina lyssnarsocklar
        // innan vi börjar fråga den, annars kan första discovery-frågan
        // hinna skickas innan servern är redo.
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            startRadarSession(RadarUdpClient(null))
        }
    }

    fun connect(ssid: String, password: String) {
        settings.save(ssid, password)
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
                            startRadarSession(RadarUdpClient(state.network))
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

    private fun startRadarSession(udpClient: RadarUdpClient) {
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
                    startSpokeListener(udpClient)
                }
        }
    }

    private fun startSpokeListener(udpClient: RadarUdpClient) {
        val decoder = FurunoSpokeDecoder() // en instans per session – encoding 2/3 är delta-kodat
        viewModelScope.launch {
            udpClient.listenForSpokes()
                .catch { e ->
                    FileLogger.log("ERROR", "$TAG: Spoke-lyssnare kraschade", e)
                    _appState.value = RadarAppState.Error(
                        "Tappade anslutningen till spoke-strömmen: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
                .collect { raw ->
                    try {
                        val spokes = decoder.decodeFrame(raw)
                        if (spokes.isEmpty()) return@collect

                        val updated = _spokeBuffer.value.copyOf()
                        for (spoke in spokes) {
                            val index = ((spoke.angle / (2 * Math.PI)) * ANGLE_STEPS)
                                .toInt()
                                .coerceIn(0, ANGLE_STEPS - 1)
                            updated[index] = spoke.intensities
                        }
                        _spokeBuffer.value = updated
                    } catch (e: Exception) {
                        // Ett enskilt trasigt/oväntat paket ska aldrig krascha
                        // appen – logga och hoppa bara över det.
                        FileLogger.log("WARN", "$TAG: Kunde inte avkoda spoke-paket (${raw.size} B)", e)
                    }
                }
        }
    }

    fun reset() {
        _appState.value = RadarAppState.Disconnected
        _connectedNetwork.value = null
        emulator?.stop()
        emulator = null
        _isEmulatorMode.value = false
    }

    override fun onCleared() {
        super.onCleared()
        emulator?.stop()
    }
}
