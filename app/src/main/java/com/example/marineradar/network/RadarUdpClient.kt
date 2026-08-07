package com.example.marineradar.network

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import com.example.marineradar.debug.FileLogger
import com.example.marineradar.debug.PacketLogEntry
import com.example.marineradar.debug.PacketLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

private fun ByteArray.hexHead(n: Int = 24): String =
    take(n).joinToString(" ") { "%02X".format(it) } + if (size > n) " …(${size} B)" else ""

data class RadarInfo(
    val ipAddress: InetAddress,
    val model: String?,
    val serialNumber: String?,
    val name: String?
)

/**
 * Låg-nivå UDP-lager, byggt på det VERIFIERADE binära Furuno NavNet-
 * protokollet (porterat från mayara-servers `src/lib/brand/furuno/`).
 * Ingen gissning längre för dessa delar – se [FurunoProtocol].
 */
class RadarUdpClient(private val network: Network?, private val context: Context? = null) {

    private companion object {
        /** Hur ofta mottagningsstatistik skrivs till loggen. */
        const val STATS_INTERVAL_MS = 5_000L
    }

    /** Skriver ut alla nätverksinterface + adresser – visar direkt om telefonen
     *  faktiskt sitter på radarns nät eller om trafiken går via mobilnätet. */
    private fun logInterfaces(reason: String) {
        try {
            val text = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp }
                .joinToString("; ") { iface ->
                    val addrs = iface.inetAddresses.asSequence()
                        .mapNotNull { it.hostAddress }
                        .joinToString(",")
                    "${iface.name}[mc=${iface.supportsMulticast()},lo=${iface.isLoopback}]=$addrs"
                }
            FileLogger.log("INFO", "RadarUdpClient: interface ($reason): $text")
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarUdpClient: kunde inte lista interface: ${e.message}")
        }
    }

    /**
     * Skickar de tre discovery-paketen (beacon-fråga, modell-fråga,
     * klient-annonsering) som broadcast till [FurunoProtocol.BEACON_PORT],
     * och lyssnar upp till [timeoutMs] efter svar. Radarn kan svara med
     * flera paket (beacon-rapport OCH modell-rapport separat) – vi
     * fortsätter lyssna tills vi antingen fått modell-rapporten (170 byte,
     * ger oss modellnamn+serienr) eller tiden tar slut.
     *
     * [overrideTargets]: om satt, används dessa mål istället för de
     * automatiskt beräknade broadcast-adresserna – används av
     * emulatorläget för att skicka till 127.0.0.1 istället för att
     * broadcasta på ett riktigt nätverk.
     */
    fun discover(timeoutMs: Int = 8000, overrideTargets: List<String>? = null) = callbackFlow<RadarInfo> {
        network?.let { NetworkDiagnostics.logInterfaceDetails(it) }
        logInterfaces("discovery")
        val broadcastTargets = overrideTargets
            ?: network?.let { NetworkDiagnostics.broadcastTargets(it) }
            ?: listOf("127.0.0.1")
        FileLogger.log(
            "INFO",
            "RadarUdpClient: discovery mot port ${FurunoProtocol.BEACON_PORT}, broadcast-mål: $broadcastTargets"
        )

        // VIKTIGT: riktig Furuno-hårdvara skickar sina beacon-/modell-svar
        // till den FASTA porten 10010 (broadcast), inte tillbaka till vår
        // avsändarport. Lyssnar vi på en ephemeral port ser vi därför aldrig
        // svaret från en riktig radar (emulatorn svarar till avsändarporten
        // och dolde buggen). Vi binder därför 10010 med SO_REUSEADDR, och
        // faller bara tillbaka till ephemeral port om porten är upptagen.
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(FurunoProtocol.BEACON_PORT))
                broadcast = true
                soTimeout = 1000
            }.also { network?.bindSocket(it) }
        } catch (bindError: Exception) {
            FileLogger.log(
                "WARN",
                "RadarUdpClient: kunde inte binda ${FurunoProtocol.BEACON_PORT} " +
                    "(${bindError.message}) – faller tillbaka till ephemeral port"
            )
            try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                    broadcast = true
                    soTimeout = 1000
                }.also { network?.bindSocket(it) }
            } catch (e: Exception) {
                FileLogger.log("ERROR", "RadarUdpClient: kunde inte skapa discovery-socket", e)
                close(e)
                return@callbackFlow
            }
        }

        FileLogger.log(
            "INFO",
            "RadarUdpClient: discovery-socket bunden till lokal port ${socket.localPort} " +
                "(önskad ${FurunoProtocol.BEACON_PORT}), broadcast=${socket.broadcast}, " +
                "nätverksbunden=${network != null}"
        )

        fun sendPacket(name: String, bytes: ByteArray) {
            for (targetIp in broadcastTargets) {
                try {
                    val target = InetAddress.getByName(targetIp)
                    socket.send(DatagramPacket(bytes, bytes.size, target, FurunoProtocol.BEACON_PORT))
                    PacketLogger.log(
                        PacketLogEntry(
                            timestampMs = System.currentTimeMillis(),
                            direction = PacketLogEntry.Direction.TX,
                            remoteHost = "$targetIp ($name)",
                            remotePort = FurunoProtocol.BEACON_PORT,
                            localPort = socket.localPort,
                            length = bytes.size,
                            data = bytes
                        )
                    )
                } catch (e: Exception) {
                    FileLogger.log("WARN", "RadarUdpClient: kunde inte skicka $name till $targetIp: ${e.message}")
                }
            }
        }

        try {
            sendPacket("REQUEST_BEACON", FurunoProtocol.REQUEST_BEACON_PACKET)
            sendPacket("REQUEST_MODEL", FurunoProtocol.REQUEST_MODEL_PACKET)
            sendPacket("ANNOUNCE_CLIENT", FurunoProtocol.ANNOUNCE_CLIENT_PACKET)

            val deadline = System.currentTimeMillis() + timeoutMs
            var lastRadarAddress: InetAddress? = null
            var name: String? = null
            var model: String? = null
            var serial: String? = null

            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    lastRadarAddress = packet.address

                    PacketLogger.log(
                        PacketLogEntry(
                            timestampMs = System.currentTimeMillis(),
                            direction = PacketLogEntry.Direction.RX,
                            remoteHost = packet.address.hostAddress ?: "?",
                            remotePort = packet.port,
                            localPort = socket.localPort,
                            length = packet.length,
                            data = data
                        )
                    )

                    FileLogger.log(
                        "INFO",
                        "RadarUdpClient: discovery RX ${packet.length} B från " +
                            "${packet.address.hostAddress}:${packet.port} → ${data.hexHead()}"
                    )

                    if (data.size == FurunoProtocol.MODEL_REPORT_LENGTH) {
                        model = FurunoProtocol.cString(
                            data.copyOfRange(
                                FurunoProtocol.MODEL_NAME_OFFSET,
                                FurunoProtocol.MODEL_NAME_OFFSET + FurunoProtocol.MODEL_NAME_LENGTH
                            )
                        )
                        serial = FurunoProtocol.cString(
                            data.copyOfRange(
                                FurunoProtocol.SERIAL_NO_OFFSET,
                                FurunoProtocol.SERIAL_NO_OFFSET + FurunoProtocol.SERIAL_NO_LENGTH
                            )
                        )
                        FileLogger.log("INFO", "RadarUdpClient: modell-rapport mottagen: model=$model serial=$serial")
                        break
                    } else if (data.size >= FurunoProtocol.BEACON_REPORT_MIN_LENGTH &&
                        data.copyOfRange(0, 11).contentEquals(FurunoProtocol.BEACON_REPORT_HEADER)
                    ) {
                        val nameBytes = data.copyOfRange(16, minOf(24, data.size))
                        name = FurunoProtocol.cString(nameBytes)
                        FileLogger.log("INFO", "RadarUdpClient: beacon-rapport mottagen: name=$name")
                    } else {
                        FileLogger.log(
                            "WARN",
                            "RadarUdpClient: okänt discovery-svar (${data.size} B) – varken " +
                                "modell-rapport (${FurunoProtocol.MODEL_REPORT_LENGTH} B) eller beacon-header"
                        )
                    }
                } catch (_: SocketTimeoutException) {
                    // normalt, fortsätt tills deadline
                }
            }

            if (lastRadarAddress != null && (model != null || name != null)) {
                trySend(RadarInfo(lastRadarAddress, model, serial, name))
            } else {
                throw SocketTimeoutException(
                    "Inget svar från radarn på ${FurunoProtocol.BEACON_PORT} inom ${timeoutMs}ms"
                )
            }
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarUdpClient: discovery misslyckades (${e.javaClass.simpleName}: ${e.message})")
            close(e)
        }

        awaitClose { socket.close() }
    }.flowOn(Dispatchers.IO)

    /**
     * Lyssnar efter spoke-data på BÅDE multicast-adressen
     * ([FurunoProtocol.SPOKE_MULTICAST_IP], används av kablade
     * DRS/NXT/FAR-modeller) OCH broadcast ([FurunoProtocol.BROADCAST_IP])
     * samtidigt på [FurunoProtocol.DATA_PORT] – eftersom vi ännu inte vet
     * 100% säkert vilket DRS4W faktiskt gör i praktiken, täcker vi båda.
     */
    fun listenForSpokes() = callbackFlow<ByteArray> {
        FileLogger.log(
            "INFO",
            "RadarUdpClient: lyssnar efter spoke-data på port ${FurunoProtocol.DATA_PORT} (multicast + broadcast)"
        )

        logInterfaces("spoke-lyssning")

        try {
            coroutineScope {
                launch { listenBroadcastSpokes(this@callbackFlow) }
                launch { listenMulticastSpokes(this@callbackFlow) }
            }
        } catch (e: Exception) {
            FileLogger.log("ERROR", "RadarUdpClient: spoke-lyssnare avbröts", e)
            close(e)
        }

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private fun listenBroadcastSpokes(scope: ProducerScope<ByteArray>) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(FurunoProtocol.DATA_PORT))
                soTimeout = 2000
            }
            network?.bindSocket(socket)
            FileLogger.log(
                "INFO",
                "RadarUdpClient: broadcast spoke-socket bunden till ${socket.localPort}"
            )

            val buf = ByteArray(8192)
            var packets = 0L
            var bytes = 0L
            var lastStat = System.currentTimeMillis()
            while (scope.isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (packets == 0L) {
                        FileLogger.log(
                            "INFO",
                            "RadarUdpClient: FÖRSTA broadcast-spoke-paketet " +
                                "(${packet.length} B) från ${packet.address.hostAddress}:${packet.port} → " +
                                packet.data.copyOf(packet.length).hexHead()
                        )
                    }
                    packets++
                    bytes += packet.length
                    emitSpokePacket(scope, packet, "broadcast")
                } catch (_: SocketTimeoutException) {
                    // normalt, fortsätt
                }
                val now = System.currentTimeMillis()
                if (now - lastStat >= STATS_INTERVAL_MS) {
                    FileLogger.log(
                        if (packets == 0L) "WARN" else "INFO",
                        "RadarUdpClient: broadcast-statistik – $packets paket / $bytes B " +
                            "på port ${FurunoProtocol.DATA_PORT} de senaste " +
                            "${(now - lastStat) / 1000}s" +
                            if (packets == 0L) " (INGEN data från radarn)" else ""
                    )
                    packets = 0; bytes = 0; lastStat = now
                }
            }
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarUdpClient: broadcast spoke-lyssnare fel: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    private fun listenMulticastSpokes(scope: ProducerScope<ByteArray>) {
        var socket: MulticastSocket? = null
        // Utan MulticastLock (och CHANGE_WIFI_MULTICAST_STATE i manifestet)
        // filtrerar Androids WiFi-drivrutin bort alla multicast-paket –
        // joinGroup() lyckas men vi får aldrig någon spoke-data.
        val multicastLock = try {
            (context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("marineradar-spokes")
                ?.apply {
                    setReferenceCounted(true)
                    acquire()
                    FileLogger.log("INFO", "RadarUdpClient: MulticastLock taget")
                }
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarUdpClient: kunde inte ta MulticastLock: ${e.message}")
            null
        }
        try {
            val group = InetAddress.getByName(FurunoProtocol.SPOKE_MULTICAST_IP)
            socket = MulticastSocket(FurunoProtocol.DATA_PORT)
            network?.bindSocket(socket)
            val netIf = findWifiInterface()
            if (netIf != null) {
                socket.joinGroup(InetSocketAddress(group, FurunoProtocol.DATA_PORT), netIf)
            } else {
                @Suppress("DEPRECATION")
                socket.joinGroup(group)
            }
            socket.soTimeout = 2000
            FileLogger.log(
                "INFO",
                "RadarUdpClient: multicast ansluten till ${FurunoProtocol.SPOKE_MULTICAST_IP}:" +
                    "${FurunoProtocol.DATA_PORT} via interface=${netIf?.name ?: "(system-default)"}, " +
                    "MulticastLock=${multicastLock?.isHeld == true}"
            )

            val buf = ByteArray(8192)
            var packets = 0L
            var bytes = 0L
            var lastStat = System.currentTimeMillis()
            while (scope.isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (packets == 0L) {
                        FileLogger.log(
                            "INFO",
                            "RadarUdpClient: FÖRSTA multicast-spoke-paketet " +
                                "(${packet.length} B) från ${packet.address.hostAddress}:${packet.port} → " +
                                packet.data.copyOf(packet.length).hexHead()
                        )
                    }
                    packets++
                    bytes += packet.length
                    emitSpokePacket(scope, packet, "multicast")
                } catch (_: SocketTimeoutException) {
                    // normalt, fortsätt
                }
                val now = System.currentTimeMillis()
                if (now - lastStat >= STATS_INTERVAL_MS) {
                    FileLogger.log(
                        if (packets == 0L) "WARN" else "INFO",
                        "RadarUdpClient: multicast-statistik – $packets paket / $bytes B " +
                            "de senaste ${(now - lastStat) / 1000}s" +
                            if (packets == 0L) " (INGEN data på ${FurunoProtocol.SPOKE_MULTICAST_IP})" else ""
                    )
                    packets = 0; bytes = 0; lastStat = now
                }
            }
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarUdpClient: multicast spoke-lyssnare fel: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) { }
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Exception) { }
        }
    }

    private fun emitSpokePacket(scope: ProducerScope<ByteArray>, packet: DatagramPacket, via: String) {
        val data = packet.data.copyOf(packet.length)
        PacketLogger.log(
            PacketLogEntry(
                timestampMs = System.currentTimeMillis(),
                direction = PacketLogEntry.Direction.RX,
                remoteHost = "${packet.address.hostAddress ?: "?"} ($via)",
                remotePort = packet.port,
                localPort = FurunoProtocol.DATA_PORT,
                length = packet.length,
                data = data
            )
        )
        scope.trySend(data)
    }

    private fun findWifiInterface(): NetworkInterface? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                .toList()
            // Föredra ett interface på radarns vanliga subnät, men FALL INTE
            // tillbaka till "inget interface" om DHCP delat ut ett annat
            // subnät – ta då första bästa multicast-dugliga interface.
            candidates.firstOrNull { iface ->
                iface.inetAddresses.asSequence().any {
                    it.hostAddress?.startsWith(RadarProtocolConstants.RADAR_SUBNET_PREFIX) == true
                }
            } ?: candidates.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
