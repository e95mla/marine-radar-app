package com.example.marineradar.radar

import android.app.Application
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.marineradar.network.RadarUdpClient
import com.example.marineradar.network.RadarWifiManager
import com.example.marineradar.network.WifiConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val ANGLE_STEPS = 2048 // upplösning på PPI-bilden (antal spokes per varv)

sealed class RadarAppState {
    data object Disconnected : RadarAppState()
    data object ConnectingWifi : RadarAppState()
    data object DiscoveringRadar : RadarAppState()
    data class Streaming(val model: String?) : RadarAppState()
    data class Error(val message: String) : RadarAppState()
}

class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val wifiManager = RadarWifiManager(application)

    private val _appState = MutableStateFlow<RadarAppState>(RadarAppState.Disconnected)
    val appState: StateFlow<RadarAppState> = _appState.asStateFlow()

    // Cirkulär buffer: en rad intensiteter per vinkelsteg. UI:t läser
    // detta direkt varje frame för att rita PPI-bilden.
    private val _spokeBuffer = MutableStateFlow(Array(ANGLE_STEPS) { ByteArray(0) })
    val spokeBuffer: StateFlow<Array<ByteArray>> = _spokeBuffer.asStateFlow()

    private val _connectedNetwork = MutableStateFlow<Network?>(null)
    val connectedNetwork: StateFlow<Network?> = _connectedNetwork.asStateFlow()

    fun connect(ssid: String, password: String) {
        _appState.value = RadarAppState.ConnectingWifi
        viewModelScope.launch {
            wifiManager.connect(ssid, password).collect { state ->
                when (state) {
                    is WifiConnectionState.Connected -> {
                        _appState.value = RadarAppState.DiscoveringRadar
                        _connectedNetwork.value = state.network
                        startRadarSession(RadarUdpClient(state.network))
                    }
                    is WifiConnectionState.Failed -> {
                        _appState.value = RadarAppState.Error(state.reason)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startRadarSession(udpClient: RadarUdpClient) {
        viewModelScope.launch {
            udpClient.discover().collect { info ->
                _appState.value = RadarAppState.Streaming(info.model)
                startSpokeListener(udpClient)
            }
        }
    }

    private fun startSpokeListener(udpClient: RadarUdpClient) {
        viewModelScope.launch {
            udpClient.listenForSpokes().collect { raw ->
                val spoke = SpokeDecoder.decode(raw) ?: return@collect
                val index = ((spoke.angle / (2 * Math.PI)) * ANGLE_STEPS)
                    .toInt()
                    .coerceIn(0, ANGLE_STEPS - 1)

                val updated = _spokeBuffer.value.copyOf()
                updated[index] = spoke.intensities
                _spokeBuffer.value = updated
            }
        }
    }
}
