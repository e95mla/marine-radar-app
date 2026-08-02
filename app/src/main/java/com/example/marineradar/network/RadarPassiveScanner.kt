package com.example.marineradar.network

import android.net.Network
import com.example.marineradar.debug.FileLogger
import com.example.marineradar.debug.PacketLogEntry
import com.example.marineradar.debug.PacketLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enligt Mayaras egen dokumentation (docs/capturing-traffic.md,
 * docs/furuno-setup.md) sänder Furuno-radarn discovery-beacons PROAKTIVT
 * över UDP broadcast OCH multicast – klienten behöver inte fråga (det
 * skiljer sig alltså från vår tidigare $N96-frågemodell, som förutsatte
 * att radarn bara svarar på en riktad fråga).
 *
 * Den här skannern lyssnar därför PASSIVT (skickar ingenting) på en bred
 * uppsättning kandidatportar och kända multicast-adressintervall
 * samtidigt, under en konfigurerbar tidsperiod, och loggar allt som
 * kommer in i [PacketLogger] – oavsett port/avsändare. Målet är att
 * "råka höra" radarns beacon utan att behöva känna till exakt
 * port/adress i förväg.
 *
 * De exakta multicast-adresserna/portarna för Furuno finns definierade
 * i mayara-servers källkod (`src/lib/brand/furuno/protocol.rs`) – om du
 * får tag i den filen (t.ex. genom att klona repot på en dator) kan du
 * lägga till exakt de adresserna i [CANDIDATE_MULTICAST_GROUPS] nedan
 * istället för att gissa brett.
 */
class RadarPassiveScanner(private val network: Network) {

    companion object {
        // Vanliga multicast-adressintervall som marina elektronikprotokoll
        // (Navico, Furuno, Garmin m.fl.) historiskt använt. Inte en facit-
        // lista för just DRS4W, men ett rimligt startspann att lyssna brett
        // på tills exakt adress är bekräftad.
        val CANDIDATE_MULTICAST_GROUPS: List<Pair<String, List<Int>>> = listOf(
            "224.0.0.1" to listOf(10024, 10025, 10026),
            "236.6.7.5" to listOf(6678, 6679, 6680),
            "236.6.7.9" to listOf(6678, 6679, 6680),
            "236.6.7.13" to listOf(6678, 6679, 6680),
            "239.255.0.1" to listOf(2000, 10110),
            "239.255.4.5" to listOf(10024, 10025),
        )

        // Bred portlista för broadcast-lyssning (172.31.255.255).
        // Håller den under ~300 portar för att inte slå i Androids
        // gräns för samtidigt öppna filhandtag (sockets).
        val BROADCAST_PORT_CANDIDATES: List<Int> = buildList {
            addAll(listOf(10024, 10025, 10026, 10027, 10028, 10110))
            addAll(listOf(2000, 2029, 2049, 4001, 5800, 5801, 5802))
            addAll(60000..60020)
            addAll(61024..61030)
        }.distinct()
    }

    data class PassiveScanSummary(
        val portsListened: Int,
        val multicastGroupsJoined: Int,
        val packetsHeard: Int
    )

    /**
     * Lyssnar passivt i [durationMs] millisekunder på alla kandidatportar
     * (broadcast) och multicast-grupper samtidigt. Allt som kommer in
     * loggas direkt i [PacketLogger] under tiden skanningen pågår, så du
     * kan följa Paket-fliken live. Skickar en sammanfattning när klart.
     */
    fun passiveScan(durationMs: Long = 20_000) = callbackFlow<PassiveScanSummary> {
        FileLogger.log("INFO", "RadarPassiveScanner: startar passiv skanning i ${durationMs}ms")
        val packetsHeard = AtomicInteger(0)

        try {
            coroutineScope {
                for (port in BROADCAST_PORT_CANDIDATES) {
                    launch { listenOnPort(port, durationMs, packetsHeard) }
                }
                for ((group, ports) in CANDIDATE_MULTICAST_GROUPS) {
                    for (port in ports) {
                        launch { listenOnMulticast(group, port, durationMs, packetsHeard) }
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.log("ERROR", "RadarPassiveScanner: fel under passiv skanning", e)
        }

        val summary = PassiveScanSummary(
            portsListened = BROADCAST_PORT_CANDIDATES.size,
            multicastGroupsJoined = CANDIDATE_MULTICAST_GROUPS.size,
            packetsHeard = packetsHeard.get()
        )
        FileLogger.log("INFO", "RadarPassiveScanner: klar. $summary")
        trySend(summary)
        close()

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private fun listenOnPort(port: Int, durationMs: Long, packetsHeard: AtomicInteger) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                soTimeout = 1000
            }
            network.bindSocket(socket)

            val deadline = System.currentTimeMillis() + durationMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    packetsHeard.incrementAndGet()
                    PacketLogger.log(
                        PacketLogEntry(
                            timestampMs = System.currentTimeMillis(),
                            direction = PacketLogEntry.Direction.RX,
                            remoteHost = packet.address.hostAddress ?: "?",
                            remotePort = packet.port,
                            localPort = port,
                            length = packet.length,
                            data = packet.data.copyOf(packet.length)
                        )
                    )
                } catch (_: SocketTimeoutException) {
                    // normalt, fortsätt lyssna till tiden är ute
                }
            }
        } catch (_: Exception) {
            // Porten kunde inte bindas (t.ex. redan upptagen) – hoppa
            // vidare, det är förväntat för en del av den breda listan.
        } finally {
            socket?.close()
        }
    }

    private fun listenOnMulticast(group: String, port: Int, durationMs: Long, packetsHeard: AtomicInteger) {
        var socket: MulticastSocket? = null
        try {
            val address = InetAddress.getByName(group)
            socket = MulticastSocket(port)
            network.bindSocket(socket)
            val netIf = findWifiInterface()
            if (netIf != null) {
                socket.joinGroup(InetSocketAddress(address, port), netIf)
            } else {
                @Suppress("DEPRECATION")
                socket.joinGroup(address)
            }
            socket.soTimeout = 1000

            val deadline = System.currentTimeMillis() + durationMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    packetsHeard.incrementAndGet()
                    PacketLogger.log(
                        PacketLogEntry(
                            timestampMs = System.currentTimeMillis(),
                            direction = PacketLogEntry.Direction.RX,
                            remoteHost = "${packet.address.hostAddress ?: "?"} (multicast $group)",
                            remotePort = packet.port,
                            localPort = port,
                            length = packet.length,
                            data = packet.data.copyOf(packet.length)
                        )
                    )
                } catch (_: SocketTimeoutException) {
                    // normalt
                }
            }
        } catch (_: Exception) {
            // Gruppen gick inte att gå med i – hoppa vidare.
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) { }
        }
    }

    private fun findWifiInterface(): NetworkInterface? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence().firstOrNull { iface ->
                iface.isUp && !iface.isLoopback && iface.inetAddresses.asSequence().any {
                    it.hostAddress?.startsWith(RadarProtocolConstants.RADAR_SUBNET_PREFIX) == true
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
