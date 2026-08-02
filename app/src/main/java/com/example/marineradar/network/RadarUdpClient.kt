package com.example.marineradar.network

import android.net.Network
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
class RadarUdpClient(private val network: Network) {

    /**
     * Skickar de tre discovery-paketen (beacon-fråga, modell-fråga,
     * klient-annonsering) som broadcast till [FurunoProtocol.BEACON_PORT],
     * och lyssnar upp till [timeoutMs] efter svar. Radarn kan svara med
     * flera paket (beacon-rapport OCH modell-rapport separat) – vi
     * fortsätter lyssna tills vi antingen fått modell-rapporten (170 byte,
     * ger oss modellnamn+serienr) eller tiden tar slut.
     */
    fun discover(timeoutMs: Int = 8000) = callbackFlow<RadarInfo> {
        FileLogger.log(
            "INFO",
            "RadarUdpClient: discovery mot ${FurunoProtocol.BROADCAST_IP}:${FurunoProtocol.BEACON_PORT}"
        )

        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                broadcast = true
                soTimeout = 1000
            }.also { network.bindSocket(it) }
        } catch (e: Exception) {
            FileLogger.log("ERROR", "RadarUdpClient: kunde inte skapa discovery-socket", e)
            close(e)
            return@callbackFlow
        }

        val target = InetAddress.getByName(FurunoProtocol.BROADCAST_IP)

        fun sendPacket(name: String, bytes: ByteArray) {
            socket.send(DatagramPacket(bytes, bytes.size, target, FurunoProtocol.BEACON_PORT))
            PacketLogger.log(
                PacketLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    direction = PacketLogEntry.Direction.TX,
                    remoteHost = "${FurunoProtocol.BROADCAST_IP} ($name)",
                    remotePort = FurunoProtocol.BEACON_PORT,
                    localPort = socket.localPort,
                    length = bytes.size,
                    data = bytes
                )
            )
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
            network.bindSocket(socket)

            val buf = ByteArray(8192)
            while (scope.isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    emitSpokePacket(scope, packet, "broadcast")
                } catch (_: SocketTimeoutException) {
                    // normalt, fortsätt
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
        try {
            val group = InetAddress.getByName(FurunoProtocol.SPOKE_MULTICAST_IP)
            socket = MulticastSocket(FurunoProtocol.DATA_PORT)
            network.bindSocket(socket)
            val netIf = findWifiInterface()
            if (netIf != null) {
                socket.joinGroup(InetSocketAddress(group, FurunoProtocol.DATA_PORT), netIf)
            } else {
                @Suppress("DEPRECATION")
                socket.joinGroup(group)
            }
            socket.soTimeout = 2000

            val buf = ByteArray(8192)
            while (scope.isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    emitSpokePacket(scope, packet, "multicast")
                } catch (_: SocketTimeoutException) {
                    // normalt, fortsätt
                }
            }
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarUdpClient: multicast spoke-lyssnare fel: ${e.message}")
        } finally {
            try {
                socket?.close()
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
