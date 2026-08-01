package com.example.marineradar.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.example.marineradar.debug.FileLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * Ansluter till DRS4W:s eget WiFi-nät ("FURUNO-xxxx" eller liknande SSID,
 * se radions etikett/manual) och håller kvar en [Network]-referens som
 * kan användas för att binda UDP-sockets till just det nätet – det gör
 * att appen kan prata med radarn samtidigt som telefonen inte har internet
 * via det nätet, utan att Android kopplar bort WiFi:t automatiskt.
 *
 * SSID/lösenord skrivs in av användaren i UI:t första gången (finns på
 * en etikett på radarn, eller i installationsdokumenten).
 */
sealed class WifiConnectionState {
    data object Idle : WifiConnectionState()
    data object Connecting : WifiConnectionState()
    data class Connected(val network: Network) : WifiConnectionState()
    data class Failed(val reason: String) : WifiConnectionState()
}

class RadarWifiManager(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Kopplar upp mot radarns WiFi-AP. Returnerar en flow av tillståndet;
     * senaste elementet blir [WifiConnectionState.Connected] med en
     * [Network]-referens som ska användas för alla sockets mot radarn.
     */
    fun connect(ssid: String, password: String?) = callbackFlow {
        trySend(WifiConnectionState.Connecting)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            trySend(WifiConnectionState.Failed("Kräver Android 10 eller senare"))
            close()
            return@callbackFlow
        }

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
        if (!password.isNullOrEmpty()) {
            specifierBuilder.setWpa2Passphrase(password)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                FileLogger.log("INFO", "RadarWifiManager: nätverk tillgängligt för $ssid")
                // Viktigt: binda processen så DNS/vanlig trafik också kan
                // routas hit vid behov, men vi använder ändå explicit
                // socket-binding i RadarUdpClient för säkerhets skull.
                connectivityManager.bindProcessToNetwork(network)
                trySend(WifiConnectionState.Connected(network))
            }

            override fun onUnavailable() {
                FileLogger.log("WARN", "RadarWifiManager: '$ssid' onUnavailable (fel SSID/lösenord, eller radarn är avstängd/utom räckhåll?)")
                trySend(WifiConnectionState.Failed("Kunde inte ansluta till $ssid – kontrollera att radarn är påslagen och SSID/lösenord stämmer"))
            }

            override fun onLost(network: Network) {
                FileLogger.log("WARN", "RadarWifiManager: anslutning till '$ssid' tappad")
                trySend(WifiConnectionState.Failed("Anslutningen till $ssid tappades"))
            }
        }

        FileLogger.log("INFO", "RadarWifiManager: begär nätverk för SSID '$ssid'")
        connectivityManager.requestNetwork(request, callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
            connectivityManager.bindProcessToNetwork(null)
        }
    }
}
