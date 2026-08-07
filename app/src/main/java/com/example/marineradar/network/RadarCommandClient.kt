package com.example.marineradar.network

import android.net.Network
import com.example.marineradar.debug.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val powerTransmit: Boolean = false,
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
 * [LOGIN_PORT] är nu 10010 (BEACON_PORT), i linje med mayara-server som
 * inleder handskakningen mot radarns egen beacon-/källport. Tidigare
 * antog vi 10000 (BASE_PORT), vilket ger connection refused/timeout mot
 * riktig DRS4W-hårdvara. Kommandoporten läses ut ur login-svaret som
 * BASE_PORT + offset.
 */
class RadarCommandClient(private val network: Network? = null) {

    companion object {
        /**
         * TCP-porten för login-handskakningen. mayara-server ansluter till
         * radarns EGEN käll-/beaconport (10010) – inte 10000. Med 10000 får
         * man connection refused / timeout mot riktig DRS4W-hårdvara.
         */
        const val LOGIN_PORT = FurunoProtocol.BEACON_PORT // 10010

        /** Intervall för AliveCheck ($RE3) – radarn stänger kanalen utan dessa. */
        const val ALIVE_INTERVAL_MS = 5_000L

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
    private var aliveJob: Job? = null
    private var aliveCount = 0
    private var reportCount = 0

    /**
     * Skapar en TCP-socket som är bunden till radarns WiFi-nätverk.
     *
     * KRITISKT: utan [Network.bindSocket] router Android trafiken via det
     * "default"-nätverk som har internet (mobildata/rmnet0), eftersom
     * radar-WiFi:t saknar NET_CAPABILITY_INTERNET. Symptom: connect-timeout
     * "from /10.x.x.x" istället för telefonens 172.31-adress.
     */
    private fun newBoundSocket(): Socket {
        val s = Socket()
        try {
            network?.bindSocket(s)
        } catch (e: Exception) {
            FileLogger.log("WARN", "RadarCommandClient: kunde inte binda socket till radar-nätverket", e)
        }
        return s
    }

    private val _controls = MutableStateFlow(RadarControls())
    val controls: StateFlow<RadarControls> = _controls.asStateFlow()

    /**
     * Loggar in mot radarn och öppnar en långlivad kommandokanal.
     * Blockerar (suspenderar) tills anslutningen stängs eller misslyckas
     * – kör i en egen coroutine.
     */
    suspend fun connectAndListen(radarIp: InetAddress) = withContext(Dispatchers.IO) {
        try {
            FileLogger.log("INFO", "RadarCommandClient: loggar in mot $radarIp:$LOGIN_PORT (TCP)")
            val port = login(radarIp)
            FileLogger.log("INFO", "RadarCommandClient: inloggad mot $radarIp, kommandoport=$port")

            val s = newBoundSocket()
            FileLogger.log("INFO", "RadarCommandClient: öppnar kommandosocket mot $radarIp:$port")
            s.connect(InetSocketAddress(radarIp, port), 5000)
            // Radarn släpper tysta kontrollsocketar – håll TCP-nivån vid liv också.
            s.keepAlive = true
            s.tcpNoDelay = true
            socket = s
            writer = s.getOutputStream()
            _controls.value = _controls.value.copy(connected = true)
            FileLogger.log("INFO", "RadarCommandClient: kommandokanal öppen (lokal port ${s.localPort})")

            // Initiala statusfrågor (motsvarar Command::init() i command.rs).
            // $R96 (Modules) är obligatorisk – den identifierar radarmodellen
            // och får radarn att börja rapportera överhuvudtaget.
            sendCommand('R', 0x96, emptyList())
            sendCommand('R', 0x8E, listOf(0))
            sendCommand('R', 0x8F, listOf(0))
            sendCommand('R', 0x69, emptyList())
            sendCommand('R', 0x62, emptyList())
            sendCommand('R', 0x63, emptyList())
            sendCommand('R', 0x64, emptyList())
            sendCommand('R', 0x65, emptyList())
            sendCommand('R', 0x77, emptyList())
            sendCommand('R', 0xE8, emptyList())

            // AliveCheck: mayara skickar $RE3 var 5:e sekund. Utan den
            // stänger radarn kommandokanalen efter ~15 s och spoke-strömmen
            // dör med den.
            aliveJob = CoroutineScope(Dispatchers.IO).launch {
                while (isActive) {
                    delay(ALIVE_INTERVAL_MS)
                    sendCommand('R', 0xE3, emptyList())
                    aliveCount++
                    if (aliveCount % 6 == 0) {
                        FileLogger.log(
                            "INFO",
                            "RadarCommandClient: $aliveCount AliveCheck (\$RE3) skickade, " +
                                "$reportCount rapporter mottagna från radarn" +
                                if (reportCount == 0) " (radarn svarar INTE på kommandokanalen)" else ""
                        )
                    }
                }
            }

            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (isActive) {
                val line = reader.readLine()
                if (line == null) {
                    FileLogger.log("WARN", "RadarCommandClient: radarn stängde kommandokanalen (EOF)")
                    break
                }
                handleReportLine(line)
            }
        } catch (e: Exception) {
            FileLogger.log(
                "ERROR",
                "RadarCommandClient: kommandokanal avslutad (${e.javaClass.simpleName}: ${e.message})",
                e
            )
        } finally {
            _controls.value = _controls.value.copy(connected = false)
            close()
        }
    }

    fun close() {
        aliveJob?.cancel()
        aliveJob = null
        try {
            socket?.close()
        } catch (_: Exception) { }
        socket = null
        writer = null
    }

    private fun login(radarIp: InetAddress): Int {
        newBoundSocket().use { s ->
            s.connect(InetSocketAddress(radarIp, LOGIN_PORT), 3000)
            s.soTimeout = 3000
            FileLogger.log(
                "INFO",
                "RadarCommandClient: login-socket ansluten från ${s.localAddress?.hostAddress}:${s.localPort}"
            )
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
            FileLogger.log(
                "INFO",
                "RadarCommandClient: login-svar OK, portbytes=" +
                    portBytes.joinToString(" ") { "%02X".format(it) }
            )
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
            if (idHex != 0xE3) {
                FileLogger.log("INFO", "RadarCommandClient: TX ${sb.toString().trim()}")
            }
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
        FileLogger.log(
            "INFO",
            "RadarCommandClient: begär ${if (transmit) "TRANSMIT" else "STANDBY"}; " +
                "wire=\$S69,$status,0,0,60,540,0"
        )
        sendCommand('S', 0x69, listOf(status, 0, 0, 60, 540, 0))
        CoroutineScope(Dispatchers.IO).launch {
            delay(750)
            FileLogger.log("INFO", "RadarCommandClient: verifierar effektläge med \$R69")
            sendCommand('R', 0x69, emptyList())
        }
    }

    /**
     * Stega räckvidden ett steg upp/ned i Furunos wire-tabell.
     *
     * Wire-indexen är INTE sekventiella (1/16 NM = index 21), så vi stegar i
     * [FurunoProtocol.WIRE_INDEX_ORDER] som är sorterad efter meter. Tidigare
     * användes `indexOfFirst { meters >= current }`, vilket returnerade -1 när
     * radarn stod på största räckvidden och då hoppade tillbaka till minsta –
     * och vid "-" från minsta hände ingenting alls.
     */
    fun stepRange(up: Boolean) {
        val order = FurunoProtocol.WIRE_INDEX_ORDER
        val currentMeters = _controls.value.rangeMeters
        // Närmaste index till nuvarande räckvidd (robust även om radarn
        // rapporterar en räckvidd som inte finns exakt i tabellen).
        val currentIdx = order.indices.minByOrNull { i ->
            kotlin.math.abs((FurunoProtocol.WIRE_INDEX_TO_METERS[order[i]] ?: 0) - currentMeters)
        } ?: 0
        val newIdx = (currentIdx + if (up) 1 else -1).coerceIn(0, order.size - 1)
        if (newIdx == currentIdx) {
            FileLogger.log(
                "INFO",
                "RadarCommandClient: räckvidd redan på ${if (up) "max" else "min"} " +
                    "(${currentMeters} m) – inget kommando skickat"
            )
            return
        }
        val wireIndex = order[newIdx]
        val meters = FurunoProtocol.WIRE_INDEX_TO_METERS[wireIndex] ?: currentMeters
        FileLogger.log(
            "INFO",
            "RadarCommandClient: räckvidd ${currentMeters} m -> ${meters} m " +
                "(wireIndex=$wireIndex, unit=0/NM, drid=0)"
        )
        // Optimistisk lokal uppdatering: DRS4W svarar inte alltid med $N62 på
        // eget initiativ, och utan detta stod siffran still i UI:t även när
        // radarn faktiskt bytte räckvidd. Bekräftelsen nedan korrigerar värdet
        // om radarn valde något annat.
        _controls.value = _controls.value.copy(rangeMeters = meters)
        sendCommand('S', 0x62, listOf(wireIndex, 0, 0))
        verifyAfterSet(0x62, "räckvidd")
    }

    fun setGain(auto: Boolean, value: Int) {
        FileLogger.log("INFO", "RadarCommandClient: sätter gain auto=$auto värde=$value")
        _controls.value = _controls.value.copy(gainAuto = auto, gainValue = value)
        sendCommand('S', 0x63, listOf(if (auto) 1 else 0, value, 0, 80, 0))
        verifyAfterSet(0x63, "gain")
    }

    fun setSea(auto: Boolean, value: Int) {
        FileLogger.log("INFO", "RadarCommandClient: sätter sea auto=$auto värde=$value")
        _controls.value = _controls.value.copy(seaAuto = auto, seaValue = value)
        sendCommand('S', 0x64, listOf(if (auto) 1 else 0, value, 50, 0, 0, 0))
        verifyAfterSet(0x64, "sea")
    }

    fun setRain(auto: Boolean, value: Int) {
        FileLogger.log("INFO", "RadarCommandClient: sätter rain auto=$auto värde=$value")
        _controls.value = _controls.value.copy(rainAuto = auto, rainValue = value)
        sendCommand('S', 0x65, listOf(if (auto) 1 else 0, value, 0, 0, 0, 0))
        verifyAfterSet(0x65, "rain")
    }

    /**
     * Fråga radarn om aktuellt värde strax efter ett set-kommando, så att
     * loggen visar om radarn faktiskt accepterade ändringen (samma mönster som
     * verifieringen av effektläget).
     */
    private fun verifyAfterSet(idHex: Int, label: String) {
        CoroutineScope(Dispatchers.IO).launch {
            delay(750)
            FileLogger.log("INFO", "RadarCommandClient: verifierar $label med \$R%02X".format(idHex))
            sendCommand('R', idHex, emptyList())
        }
    }

    // -------------------------------------------------------------------
    // Rapport-tolkning (förenklad delmängd av report.rs' process_report)
    // -------------------------------------------------------------------

    private fun handleReportLine(rawLine: String) {
        // mayara letar upp FÖRSTA '$' i bufferten istället för att kräva att
        // raden börjar med "$N" – radarn kan skicka partiella skrivningar och
        // skräptecken före rapporten, och då tappade vi hela raden.
        val dollar = rawLine.indexOf('$')
        if (dollar < 0) return
        val line = rawLine.substring(dollar).trim()
        if (line.length < 4) return
        reportCount++
        FileLogger.log("INFO", "RadarCommandClient: rapport $line")

        val body = line.substring(2)
        val commaIdx = body.indexOf(',')
        val idPart = if (commaIdx == -1) body else body.substring(0, commaIdx)
        val id = idPart.toIntOrNull(16) ?: return
        val argsPart = if (commaIdx == -1) "" else body.substring(commaIdx + 1)
        val numbers = argsPart.split(',').mapNotNull { it.trim().toDoubleOrNull() }

        when (id) {
            0x69 -> if (numbers.isNotEmpty()) {
                val status = numbers[0].toInt()
                _controls.value = _controls.value.copy(powerTransmit = status == 2)
                FileLogger.log(
                    "INFO",
                    "RadarCommandClient: bekräftat effektläge=${when (status) { 2 -> "TRANSMIT"; 1 -> "STANDBY"; else -> "OKÄNT($status)" }}"
                )
            }
            0x62 -> if (numbers.size >= 2) {
                // Svarsformat: $N62,{wireIndex},{unit},{drid}
                val wireIndex = numbers[0].toInt()
                val wireUnit = numbers[1].toInt()
                val meters = if (wireUnit == 0) FurunoProtocol.WIRE_INDEX_TO_METERS[wireIndex] else null
                if (meters != null) {
                    FileLogger.log(
                        "INFO",
                        "RadarCommandClient: bekräftad räckvidd=$meters m (wireIndex=$wireIndex)"
                    )
                    _controls.value = _controls.value.copy(rangeMeters = meters)
                } else {
                    FileLogger.log(
                        "WARN",
                        "RadarCommandClient: okänd räckvidd i \$N62 – wireIndex=$wireIndex unit=$wireUnit"
                    )
                }
            }
            0x63 -> if (numbers.size >= 2) {
                FileLogger.log(
                    "INFO",
                    "RadarCommandClient: bekräftad gain auto=${numbers[0].toInt() != 0} värde=${numbers[1].toInt()}"
                )
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
