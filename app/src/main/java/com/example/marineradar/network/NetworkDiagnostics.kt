package com.example.marineradar.network

import android.net.Network
import com.example.marineradar.debug.FileLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Vi antog tidigare att radarns nät alltid är ett /16 (broadcast
 * 172.31.255.255) enligt Furunos dokumentation. Men om telefonens
 * faktiska DHCP-lease på radarns WiFi har en ANNAN nätmask (t.ex. /24),
 * är 172.31.255.255 fel broadcast-adress för det nätet – paket skickade
 * dit kan tystas av OS-routingen istället för att nå radarn. Den här
 * filen läser ut den VERKLIGA lokala IP:n + nätmasken och räknar ut rätt
 * riktad broadcast-adress för just den, som komplement till de fasta
 * gissningarna.
 */
object NetworkDiagnostics {

    data class InterfaceInfo(
        val localAddress: String,
        val prefixLength: Short,
        val directedBroadcast: String
    )

    /** Loggar alla IPv4-adresser/nätmasker på [network]s gränssnitt – kör en gång direkt efter WiFi-anslutning. */
    fun logInterfaceDetails(network: Network) {
        val infos = getInterfaceInfo(network)
        if (infos.isEmpty()) {
            FileLogger.log("WARN", "NetworkDiagnostics: hittade inget IPv4-gränssnitt alls på det anslutna nätet!")
            return
        }
        for (info in infos) {
            FileLogger.log(
                "INFO",
                "NetworkDiagnostics: lokal IP=${info.localAddress}/${info.prefixLength} " +
                    "→ beräknad broadcast=${info.directedBroadcast}"
            )
        }
    }

    /** Alla broadcast-mål värda att prova: beräknade riktade + kända gissningar + "limited broadcast". */
    fun broadcastTargets(network: Network): List<String> {
        val targets = LinkedHashSet<String>()
        getInterfaceInfo(network).forEach { targets.add(it.directedBroadcast) }
        targets.add(FurunoProtocol.BROADCAST_IP) // 172.31.255.255, Furunos dokumenterade antagande
        targets.add("255.255.255.255") // "limited broadcast" – kräver ingen känd nätmask, funkar på länknivå
        return targets.toList()
    }

    private fun getInterfaceInfo(network: Network): List<InterfaceInfo> {
        val result = mutableListOf<InterfaceInfo>()
        try {
            // Enklaste, mest robusta metoden: gå igenom alla nätverksgränssnitt
            // på enheten och plocka ut det som har en IP i något av de kända
            // radar-subnäten (172.31.x.x). Det är samma heuristik som
            // RadarUdpClient redan använder för multicast-gränssnittet.
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue

                for (addr in iface.interfaceAddresses) {
                    val inet = addr.address
                    if (inet !is Inet4Address) continue
                    val host = inet.hostAddress ?: continue
                    if (!host.startsWith(RadarProtocolConstants.RADAR_SUBNET_PREFIX)) continue

                    val prefix = addr.networkPrefixLength
                    val broadcast = computeDirectedBroadcast(host, prefix)
                    result.add(InterfaceInfo(host, prefix, broadcast))
                }
            }
        } catch (e: Exception) {
            FileLogger.log("WARN", "NetworkDiagnostics: kunde inte läsa gränssnittsinfo: ${e.message}")
        }
        return result
    }

    private fun computeDirectedBroadcast(ip: String, prefixLength: Short): String {
        return try {
            val addrBytes = InetAddress.getByName(ip).address
            val maskBytes = ByteArray(4)
            var bitsRemaining = prefixLength.toInt()
            for (i in 0 until 4) {
                val bits = bitsRemaining.coerceIn(0, 8)
                maskBytes[i] = ((0xFF shl (8 - bits)) and 0xFF).toByte()
                bitsRemaining -= bits
            }
            val broadcastBytes = ByteArray(4)
            for (i in 0 until 4) {
                val hostPart = (addrBytes[i].toInt() and 0xFF) or (maskBytes[i].toInt().inv() and 0xFF)
                broadcastBytes[i] = hostPart.toByte()
            }
            InetAddress.getByAddress(broadcastBytes).hostAddress ?: FurunoProtocol.BROADCAST_IP
        } catch (_: Exception) {
            FurunoProtocol.BROADCAST_IP
        }
    }
}
