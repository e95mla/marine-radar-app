package com.example.marineradar.network

import com.example.marineradar.debug.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

/**
 * En lokal simulator som pratar EXAKT samma binära protokoll som en
 * riktig Furuno DRS4W (se [FurunoProtocol]) men helt över loopback
 * (127.0.0.1) – ingen riktig radar eller WiFi-anslutning behövs. Tanken
 * är att kunna testa hela appens pipeline (discovery → spoke-mottagning
 * → avkodning → PPI-rendering) även när man inte är i närheten av
 * radarn.
 *
 * Emulatorn:
 * 1. Lyssnar på port [FurunoProtocol.BEACON_PORT] och svarar på
 *    discovery-förfrågningar med en syntetisk beacon- och modell-rapport
 *    (modellnamn "DRS4W-EMU" så det syns tydligt i UI:t att det är
 *    simulerad data).
 * 2. Skickar kontinuerligt syntetiska spoke-frames (samma binärformat
 *    som en riktig radar, kodade med encoding-läge 1) till
 *    127.0.0.1:[FurunoProtocol.DATA_PORT], med en roterande "target"-
 *    blip och en fast "kustlinje"-båge så att PPI-bilden faktiskt visar
 *    något att titta på.
 */
class FurunoRadarEmulator {

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        FileLogger.log("INFO", "FurunoRadarEmulator: startar (loopback-simulator)")
        job = scope.launch(Dispatchers.IO) {
            launch { runDiscoveryResponder() }
            launch { runSpokeGenerator() }
        }
    }

    fun stop() {
        FileLogger.log("INFO", "FurunoRadarEmulator: stoppar")
        job?.cancel()
        job = null
    }

    // -------------------------------------------------------------------
    // Discovery-svarare
    // -------------------------------------------------------------------

    private suspend fun runDiscoveryResponder() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), FurunoProtocol.BEACON_PORT))
                soTimeout = 1000
            }
            FileLogger.log("INFO", "FurunoRadarEmulator: lyssnar på discovery, 127.0.0.1:${FurunoProtocol.BEACON_PORT}")

            val buf = ByteArray(2048)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    FileLogger.log(
                        "INFO",
                        "FurunoRadarEmulator: discovery-förfrågan mottagen (${packet.length} B) från ${packet.address}:${packet.port}"
                    )
                    sendBeaconReport(socket, packet.address, packet.port)
                    sendModelReport(socket, packet.address, packet.port)
                } catch (_: java.net.SocketTimeoutException) {
                    // normalt, fortsätt lyssna
                }
            }
        } catch (e: Exception) {
            FileLogger.log("ERROR", "FurunoRadarEmulator: discovery-svarare kraschade", e)
        } finally {
            socket?.close()
        }
    }

    private fun sendBeaconReport(socket: DatagramSocket, addr: InetAddress, port: Int) {
        val name = "DRS4WEM".padEnd(8, 0.toChar()).toByteArray(Charsets.US_ASCII).copyOf(8)
        val packet = ByteArray(32)
        FurunoProtocol.BEACON_REPORT_HEADER.copyInto(packet, 0)
        packet[11] = 0x18 // length = 24
        name.copyInto(packet, 16)
        socket.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun sendModelReport(socket: DatagramSocket, addr: InetAddress, port: Int) {
        val packet = ByteArray(FurunoProtocol.MODEL_REPORT_LENGTH)
        val model = "DRS4W-EMU".toByteArray(Charsets.US_ASCII)
        model.copyInto(packet, FurunoProtocol.MODEL_NAME_OFFSET, 0, minOf(model.size, FurunoProtocol.MODEL_NAME_LENGTH))
        val serial = "EMU-000001".toByteArray(Charsets.US_ASCII)
        serial.copyInto(packet, FurunoProtocol.SERIAL_NO_OFFSET, 0, minOf(serial.size, FurunoProtocol.SERIAL_NO_LENGTH))
        socket.send(DatagramPacket(packet, packet.size, addr, port))
    }

    // -------------------------------------------------------------------
    // Spoke-generator
    // -------------------------------------------------------------------

    private suspend fun runSpokeGenerator() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null)
            val target = InetSocketAddress(InetAddress.getByName("127.0.0.1"), FurunoProtocol.DATA_PORT)
            FileLogger.log("INFO", "FurunoRadarEmulator: skickar syntetiska spokes till 127.0.0.1:${FurunoProtocol.DATA_PORT}")

            val sweepLen = 430 // matchar DRS4W enligt report.rs-kommentar
            val spokesPerFrame = 41
            var currentAngle = 0
            var frameSeq = 0
            var targetAngle = 0

            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val frame = buildFrame(sweepLen, spokesPerFrame, currentAngle, targetAngle, frameSeq)
                socket.send(DatagramPacket(frame, frame.size, target))

                currentAngle = (currentAngle + spokesPerFrame) % FurunoProtocol.SPOKES_PER_REVOLUTION
                targetAngle = (targetAngle + 3) % FurunoProtocol.SPOKES_PER_REVOLUTION
                frameSeq++
                delay(50)
            }
        } catch (e: Exception) {
            FileLogger.log("ERROR", "FurunoRadarEmulator: spoke-generator kraschade", e)
        } finally {
            socket?.close()
        }
    }

    private fun buildFrame(
        sweepLen: Int,
        spokeCount: Int,
        startAngle: Int,
        targetAngle: Int,
        seq: Int
    ): ByteArray {
        val wireIndex = 6 // ~2 NM, se FurunoProtocol.WIRE_INDEX_TO_METERS
        val scale = sweepLen

        val header = ByteArray(16)
        header[0] = FurunoProtocol.FRAME_MAGIC.toByte()
        header[1] = (seq and 0xFF).toByte()
        header[9] = ((spokeCount shl 1) and 0xFF).toByte()
        header[10] = (sweepLen and 0xFF).toByte()
        header[11] = (((sweepLen shr 8) and 0x07) or (1 shl 3)).toByte() // encoding = 1
        header[12] = (wireIndex and 0x3F).toByte()
        header[14] = (scale and 0xFF).toByte()
        header[15] = ((scale shr 8) and 0x07).toByte()

        val body = ArrayList<Byte>(spokeCount * (4 + 32))
        for (i in 0 until spokeCount) {
            val angle = (startAngle + i) % FurunoProtocol.SPOKES_PER_REVOLUTION
            body.add((angle and 0xFF).toByte())
            body.add(((angle shr 8) and 0x1F).toByte())
            body.add((angle and 0xFF).toByte())
            body.add(((angle shr 8) and 0x1F).toByte())

            val values = generateSweep(sweepLen, angle, targetAngle)
            body.addAll(encodeMode1(values).toList())
        }

        return header + body.toByteArray()
    }

    private fun generateSweep(sweepLen: Int, angle: Int, targetAngle: Int): IntArray {
        val values = IntArray(sweepLen)

        if (angle in 3000..3399) {
            for (i in 180 until minOf(230, sweepLen)) {
                values[i] = 160
            }
        }

        val angleDiff = minOf(
            Math.floorMod(angle - targetAngle, FurunoProtocol.SPOKES_PER_REVOLUTION),
            Math.floorMod(targetAngle - angle, FurunoProtocol.SPOKES_PER_REVOLUTION)
        )
        if (angleDiff <= 5) {
            for (i in 60 until minOf(70, sweepLen)) {
                values[i] = 220
            }
        }

        if (Random.nextInt(100) < 5) {
            val i = Random.nextInt(minOf(40, sweepLen))
            values[i] = 40
        }

        return values
    }

    private fun encodeMode1(values: IntArray): ByteArray {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < values.size) {
            val v = values[i] and 0xFE
            var runLen = 1
            while (i + runLen < values.size && values[i + runLen] == values[i]) runLen++

            out.add(v.toByte())
            var remaining = runLen - 1
            while (remaining > 0) {
                val chunk = minOf(remaining, 127)
                out.add((((chunk shl 1) or 1) and 0xFF).toByte())
                remaining -= chunk
            }
            i += runLen
        }
        while (out.size % 4 != 0) out.add(0)
        return out.toByteArray()
    }
}
