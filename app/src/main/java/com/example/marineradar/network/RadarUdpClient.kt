package com.example.marineradar.network

import android.net.Network
import com.example.marineradar.debug.FileLogger
import com.example.marineradar.debug.PacketLogEntry
import com.example.marineradar.debug.PacketLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Furuno DRS4W ("1st Watch") pratar på det fasta subnätet 172.31.0.0/16.
 * Telefonen måste ha en IP i det spannet på WiFi-gränssnittet – det sköts
 * normalt av radarns egen DHCP-server när du ansluter till dess AP, men
 * verifiera i telefonens WiFi-inställningar om discovery inte hittar
 * något (Inställningar > WiFi > radarns nät > IP-info).
 *
 * VIKTIGT om portnummer nedan:
 * Furuno-familjens exakta UDP-portar för discovery/beacon respektive
 * spoke-data är INTE officiellt publicerade av Furuno. Värdena nedan är
 * platshållare/vanliga gissningar baserade på hur liknande marina
 * radar-protokoll (Navico/Garmin/Furuno-NXT) brukar vara uppbyggda.
 * Innan du litar på dem: gör en paketfångst med Wireshark medan den
 * officiella "Marine Radar"-appen (iOS) eller mayara-server pratar med
 * din DRS4W, och justera portarna/parsningen efter vad du faktiskt ser.
 * Se docs/capturing-traffic.md i github.com/MarineYachtRadar/mayara-server
 * för hur en sådan fångst görs, och src/ i samma repo för referens-
 * implementationen att jämföra mot.
 */
object RadarProtocolConstants {
    const val RADAR_SUBNET_PREFIX = "172.31."

    // TODO: bekräfta exakta portar via paketfångst mot din DRS4W.
    const val DISCOVERY_PORT = 10024
    const val SPOKE_DATA_PORT = 10025
    const val COMMAND_PORT = 10026

    const val DISCOVERY_BROADCAST = "172.31.255.255"

    // $N96-frågan som (enligt Furunos setup-dokumentation) ger tillbaka
    // en 7-siffrig del-kod som identifierar radarmodellen, t.ex.
    // 0359329 = DRS4W.
    const val N96_QUERY = "\$N96,MODULES,Q*"

    val PART_CODE_TO_MODEL = mapOf(
        "0359235" to "DRS",
        "0359338" to "DRS4DL",
        "0359367" to "DRS4DL",
        "0359360" to "DRS4DNXT",
        "0359329" to "DRS4W",
        "0359421" to "DRS6ANXT",
        "0359355" to "DRS6AXCLASS",
    )
}

data class RadarInfo(val ipAddress: InetAddress, val model: String?)

/**
 * Låg-nivå UDP-lager. Allt skickas/tas emot över den [Network] som
 * [RadarWifiManager] gav oss, så trafiken går garanterat via radarns
 * WiFi och inte av misstag via mobildata.
 */
class RadarUdpClient(private val network: Network) {

    /**
     * Skickar en $N96-modulfråga som broadcast och lyssnar efter svar
     * för att identifiera radarn på nätet. Avslutas när ett svar kommer
     * eller efter timeout.
     */
    fun discover() = callbackFlow<RadarInfo> {
        FileLogger.log("INFO", "RadarUdpClient: startar discovery mot ${RadarProtocolConstants.DISCOVERY_BROADCAST}:${RadarProtocolConstants.DISCOVERY_PORT}")

        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                broadcast = true
            }.also { network.bindSocket(it) }
        } catch (e: Exception) {
            FileLogger.log("ERROR", "RadarUdpClient: kunde inte skapa discovery-socket", e)
            close(e)
            return@callbackFlow
        }

        val queryBytes = RadarProtocolConstants.N96_QUERY.toByteArray()
        val target = InetAddress.getByName(RadarProtocolConstants.DISCOVERY_BROADCAST)
        val packet = DatagramPacket(
            queryBytes, queryBytes.size, target,
            RadarProtocolConstants.DISCOVERY_PORT
        )

        try {
            socket.send(packet)
            PacketLogger.log(
                PacketLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    direction = PacketLogEntry.Direction.TX,
                    remoteHost = target.hostAddress ?: RadarProtocolConstants.DISCOVERY_BROADCAST,
                    remotePort = RadarProtocolConstants.DISCOVERY_PORT,
                    localPort = socket.localPort,
                    length = queryBytes.size,
                    data = queryBytes
                )
            )

            val buf = ByteArray(2048)
            val receivePacket = DatagramPacket(buf, buf.size)
            socket.soTimeout = 5000
            socket.receive(receivePacket)

            PacketLogger.log(
                PacketLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    direction = PacketLogEntry.Direction.RX,
                    remoteHost = receivePacket.address.hostAddress ?: "?",
                    remotePort = receivePacket.port,
                    localPort = socket.localPort,
                    length = receivePacket.length,
                    data = receivePacket.data.copyOf(receivePacket.length)
                )
            )

            val text = String(receivePacket.data, 0, receivePacket.length)
            val partCode = Regex("0359\\d{3}").find(text)?.value
            val model = partCode?.let { RadarProtocolConstants.PART_CODE_TO_MODEL[it] }

            trySend(RadarInfo(receivePacket.address, model))
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarUdpClient: discovery misslyckades (${e.javaClass.simpleName}: ${e.message})")
            close(e)
        }

        awaitClose { socket.close() }
    }.flowOn(Dispatchers.IO)

    /**
     * Öppnar en lyssnande socket på spoke-data-porten och strömmar råa
     * UDP-payloads vidare. [SpokeDecoder] ansvarar för att tolka
     * innehållet.
     */
    fun listenForSpokes() = callbackFlow<ByteArray> {
        FileLogger.log("INFO", "RadarUdpClient: lyssnar efter spoke-data på port ${RadarProtocolConstants.SPOKE_DATA_PORT}")

        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(RadarProtocolConstants.SPOKE_DATA_PORT))
            }.also { network.bindSocket(it) }
        } catch (e: Exception) {
            FileLogger.log("ERROR", "RadarUdpClient: kunde inte binda spoke-socket (port upptagen?)", e)
            close(e)
            return@callbackFlow
        }

        val buf = ByteArray(8192)
        try {
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val data = packet.data.copyOf(packet.length)
                PacketLogger.log(
                    PacketLogEntry(
                        timestampMs = System.currentTimeMillis(),
                        direction = PacketLogEntry.Direction.RX,
                        remoteHost = packet.address.hostAddress ?: "?",
                        remotePort = packet.port,
                        localPort = RadarProtocolConstants.SPOKE_DATA_PORT,
                        length = packet.length,
                        data = data
                    )
                )
                trySend(data)
            }
        } catch (e: Exception) {
            FileLogger.log("ERROR", "RadarUdpClient: spoke-lyssnare avbröts", e)
            close(e)
        }

        awaitClose { socket.close() }
    }.flowOn(Dispatchers.IO)
}
