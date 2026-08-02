package com.example.marineradar.network

import android.net.Network
import com.example.marineradar.debug.PacketLogEntry
import com.example.marineradar.debug.PacketLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

data class ScanResult(val port: Int, val respondersFound: Int)

/**
 * Eftersom Furunos exakta portnummer inte är officiellt dokumenterade
 * skickar den här skannern en $N96-fråga (samma som riktig discovery)
 * som broadcast till en lista av troliga/vanliga portar för marin
 * elektronik och lyssnar en kort stund på varje för svar. Allt som
 * kommer tillbaka loggas i [PacketLogger] med hex-dump, oavsett vilken
 * port det gäller – så du kan se exakt vilken port radarn faktiskt
 * svarar på.
 *
 * Portlistan är en startpunkt, inte en facit-lista – lägg gärna till
 * fler kandidater i UI:t om du känner till andra marina protokoll.
 */
class RadarPortScanner(private val network: Network) {

    companion object {
        // Vanliga/kända portar inom marin elektronik och radar-liknande
        // protokoll (NMEA-over-IP, Navico/Garmin-stil discovery m.m.),
        // plus platshållarna vi redan gissar på för Furuno.
        val DEFAULT_CANDIDATE_PORTS = listOf(
            10110, // NMEA 0183 over UDP, vanlig branschstandard
            2000, 2029, 2049,
            4001, 5800, 5801, 5802,
            10024, 10025, 10026, 10027, 10028,
            60000, 60010, 61024
        )
    }

    fun scan(
        broadcastAddresses: List<String>? = null,
        ports: List<Int> = DEFAULT_CANDIDATE_PORTS,
        listenMillisPerPort: Int = 800
    ) = callbackFlow<ScanResult> {
        val targets = (broadcastAddresses ?: NetworkDiagnostics.broadcastTargets(network))
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        val n96Bytes = RadarProtocolConstants.N96_QUERY.toByteArray()

        for (port in ports) {
            var responders = 0
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                broadcast = true
                soTimeout = listenMillisPerPort
            }
            network.bindSocket(socket)

            try {
                for (target in targets) {
                    // Skicka både den gamla NMEA-gissningen OCH det verifierade
                    // binära modell-paketet (se FurunoProtocol) – ifall radarn
                    // råkar svara på fler ställen än vi tror.
                    socket.send(DatagramPacket(n96Bytes, n96Bytes.size, target, port))
                    socket.send(
                        DatagramPacket(
                            FurunoProtocol.REQUEST_MODEL_PACKET,
                            FurunoProtocol.REQUEST_MODEL_PACKET.size,
                            target,
                            port
                        )
                    )
                    PacketLogger.log(
                        PacketLogEntry(
                            timestampMs = System.currentTimeMillis(),
                            direction = PacketLogEntry.Direction.TX,
                            remoteHost = "${target.hostAddress} (scan)",
                            remotePort = port,
                            localPort = socket.localPort,
                            length = FurunoProtocol.REQUEST_MODEL_PACKET.size,
                            data = FurunoProtocol.REQUEST_MODEL_PACKET
                        )
                    )
                }

                val deadline = System.currentTimeMillis() + listenMillisPerPort
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (timeout: Exception) {
                        break
                    }
                    responders++
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
                }
            } catch (_: Exception) {
                // gå vidare till nästa port
            } finally {
                socket.close()
            }

            trySend(ScanResult(port, responders))
        }

        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)
}
