package com.example.marineradar.network

import com.example.marineradar.debug.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class RadarControls(
    val connected: Boolean = false,
    val powerTransmit: Boolean = true,
    val rangeMeters: Int = FurunoProtocol.WIRE_INDEX_TO_METERS[6] ?: 3704,
    val gainAuto: Boolean = true,
    val gainValue: Int = 50,
    val seaAuto: Boolean = true,
    val seaValue: Int = 30,
    val rainAuto: Boolean = false,
    val rainValue: Int = 20
)

/**
 * Kommandokanal, porterad från mayara-serverns `command.rs` (wire-format
 * för kommandon) och `mod.rs`/`report.rs` (login-handshake).
 *
 * VIKTIGT om [LOGIN_PORT]: login-handshakens BYTEFORMAT
 * (`LOGIN_MESSAGE`/`LOGIN_EXPECTED_HEADER`) är verifierat från
 * källkoden, men vilken TCP-PORT man ska ansluta till för att inleda
 * handskakningen är inte bekräftad mot riktig hårdvara – vi har antagit
 * `BASE_PORT` (10000) eftersom det är den logiska "basen" för hela
 * portfamiljen (beacon=+10, data=+24). Om detta visar sig fel mot din
 * riktiga DRS4W, justera konstanten och testa igen; emulatorn använder
 * samma antagande så den fungerar oavsett.
 */
class RadarCommandClient {

    companion object {
        const val LOGIN_PORT = FurunoProtocol.BASE_PORT // 10000, se varning ovan

        /** 56 byte, porterad från LOGIN_MESSAGE i protocol.rs. */
        val LOGIN_MESSAGE: ByteArray = byteArrayOf(
            0x08, 0x01, 0x00, 0x38, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x43, 0x4f, 0x50, 0x59, 0x52, 0x49, 0x47, 0x48, 0x54, 0x20, 0x28, 0x43,
            0x29, 0x20, 0x32, 0x30, 0x30, 0x31, 0x20, 0x46, 0x55, 0x52, 0x55, 0x4e,
            0x4f, 0x20, 0x45, 0x4c, 0x45, 0x43, 0x54, 0x52, 0x49, 0x43, 0x20, 0x43,
            0x4f, 0x2e, 0x2c, 0x4c, 0x54, 0x44, 0x2e, 0x20
        )

        /** 8 byte, förväntat svar efter LOGIN_MESSAGE. */
        val LOGIN_EXPECTED_HEADER: ByteArray = byteArrayOf(0x09, 0x01, 0x00, 0x0c, 0x01, 0x00, 0x00, 0x00)
    }

    private var socket: Socket? = null
    private var writer: OutputStream? = null

    private val _controls = MutableStateFlow(RadarControls())
    val controls: StateFlow<RadarControls> = _controls.asStateFlow()

    /**
     * Loggar in mot radarn och öppnar en långlivad kommandokanal.
     * Blockerar (suspenderar) tills anslutningen stängs eller misslyckas
     * – kör i en egen coroutine.
     */
    suspend fun connectAndListen(radarIp: InetAddress) = withContext(Dispatchers.IO) {
        try {
            val port = login(radarIp)
            FileLogger.log("INFO", "RadarCommandClient: inloggad mot $radarIp, kommandoport=$port")

            val s = Socket()
            s.connect(InetSocketAddress(radarIp, port), 5000)
            socket = s
            writer = s.getOutputStream()
            _controls.value = _controls.value.copy(connected = true)

            // Initiala statusfrågor (motsvarar Command::init() i command.rs, förenklat)
            sendCommand('R', 0x69, emptyList())
            sendCommand('R', 0x62, emptyList())
            sendCommand('R', 0x63, emptyList())
            sendCommand('R', 0x64, emptyList())
            sendCommand('R', 0x65, emptyList())

            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (isActive) {
                val line = reader.readLine() ?: break
                handleReportLine(line)
            }
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarCommandClient: kommandokanal avslutad: ${e.message}")
        } finally {
            _controls.value = _controls.value.copy(connected = false)
            close()
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) { }
        socket = null
        writer = null
    }

    private fun login(radarIp: InetAddress): Int {
        Socket().use { s ->
            s.connect(InetSocketAddress(radarIp, LOGIN_PORT), 3000)
            s.soTimeout = 3000
            s.getOutputStream().write(LOGIN_MESSAGE)
            s.getOutputStream().flush()

            val input = s.getInputStream()
            val header = ByteArray(8)
            readFully(input, header)
            if (!header.contentEquals(LOGIN_EXPECTED_HEADER)) {
                throw IOException(
                    "Oväntat login-svar: ${header.joinToString(" ") { "%02X".format(it) }}"
                )
            }
            val portBytes = ByteArray(4)
            readFully(input, portBytes)
            return FurunoProtocol.BASE_PORT +
                (((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF))
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw IOException("Anslutningen stängdes under läsning")
            offset += n
        }
    }

    fun sendCommand(mode: Char, idHex: Int, args: List<Int>) {
        val w = writer ?: return
        val sb = StringBuilder()
        sb.append('$').append(mode).append("%02X".format(idHex))
        for (a in args) sb.append(',').append(a)
        sb.append("\r\n")
        try {
            w.write(sb.toString().toByteArray(Charsets.US_ASCII))
            w.flush()
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarCommandClient: kunde inte skicka kommando: ${e.message}")
        }
    }

    // -------------------------------------------------------------------
    // Bekväma metoder för de vanligaste kontrollerna (wire-format från
    // command.rs' set_control-matchning)
    // -------------------------------------------------------------------

    fun setPower(transmit: Boolean) {
        val status = if (transmit) 2 else 1
        sendCommand('S', 0x69, listOf(status, 0, 0, 60, 540, 0))
    }

    fun stepRange(up: Boolean) {
        val order = FurunoProtocol.WIRE_INDEX_ORDER
        val currentMeters = _controls.value.rangeMeters
        var currentIdx = order.indexOfFirst { (FurunoProtocol.WIRE_INDEX_TO_METERS[it] ?: 0) >= currentMeters }
        if (currentIdx == -1) currentIdx = 0
        val newIdx = (currentIdx + if (up) 1 else -1).coerceIn(0, order.size - 1)
        sendCommand('S', 0x62, listOf(order[newIdx], 0, 0))
    }

    fun setGain(auto: Boolean, value: Int) {
        sendCommand('S', 0x63, listOf(if (auto) 1 else 0, value, 0, 80, 0))
    }

    fun setSea(auto: Boolean, value: Int) {
        sendCommand('S', 0x64, listOf(if (auto) 1 else 0, value, 50, 0, 0, 0))
    }

    fun setRain(auto: Boolean, value: Int) {
        sendCommand('S', 0x65, listOf(if (auto) 1 else 0, value, 0, 0, 0, 0))
    }

    // -------------------------------------------------------------------
    // Rapport-tolkning (förenklad delmängd av report.rs' process_report)
    // -------------------------------------------------------------------

    private fun handleReportLine(rawLine: String) {
        val line = rawLine.trim()
        if (!line.startsWith("\$N") || line.length < 3) return
        FileLogger.log("INFO", "RadarCommandClient: rapport $line")

        val body = line.substring(2)
        val commaIdx = body.indexOf(',')
        val idPart = if (commaIdx == -1) body else body.substring(0, commaIdx)
        val id = idPart.toIntOrNull(16) ?: return
        val argsPart = if (commaIdx == -1) "" else body.substring(commaIdx + 1)
        val numbers = argsPart.split(',').mapNotNull { it.trim().toDoubleOrNull() }

        when (id) {
            0x69 -> if (numbers.isNotEmpty()) {
                _controls.value = _controls.value.copy(powerTransmit = numbers[0].toInt() == 2)
            }
            0x62 -> if (numbers.size >= 2) {
                val wireIndex = numbers[0].toInt()
                val wireUnit = numbers[1].toInt()
                if (wireUnit == 0) {
                    FurunoProtocol.WIRE_INDEX_TO_METERS[wireIndex]?.let { meters ->
                        _controls.value = _controls.value.copy(rangeMeters = meters)
                    }
                }
            }
            0x63 -> if (numbers.size >= 2) {
                _controls.value = _controls.value.copy(
                    gainAuto = numbers[0].toInt() != 0,
                    gainValue = numbers[1].toInt()
                )
            }
            0x64 -> if (numbers.size >= 2) {
                _controls.value = _controls.value.copy(
                    seaAuto = numbers[0].toInt() != 0,
                    seaValue = numbers[1].toInt()
                )
            }
            0x65 -> if (numbers.size >= 2) {
                _controls.value = _controls.value.copy(
                    rainAuto = numbers[0].toInt() != 0,
                    rainValue = numbers[1].toInt()
                )
            }
        }
    }
}
