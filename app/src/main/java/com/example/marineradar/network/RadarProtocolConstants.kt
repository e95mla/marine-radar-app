package com.example.marineradar.network

/**
 * Äldre/kompletterande konstanter. De VERIFIERADE protokolldetaljerna
 * finns numera i [FurunoProtocol] (porterat från mayara-servers riktiga
 * källkod). Den här filen behåller några saker som fortfarande används
 * av de bredare fallback-skannrarna (RadarPortScanner, RadarPassiveScanner)
 * som ett sista-utväg-verktyg om det verifierade protokollet mot förmodan
 * inte skulle fungera exakt som i mayara-server (t.ex. pga en annan
 * firmwareversion).
 */
object RadarProtocolConstants {
    const val RADAR_SUBNET_PREFIX = "172.31."
    const val DISCOVERY_BROADCAST = FurunoProtocol.BROADCAST_IP

    // Kvar som fallback-gissning för RadarPortScanner (NMEA-stilfråga) –
    // vi vet nu att den riktiga discoveryn är binär (se FurunoProtocol),
    // men den här frågan skadar inte att också skicka som bredare test.
    const val DISCOVERY_PORT = FurunoProtocol.BEACON_PORT
    const val N96_QUERY = "\$N96,MODULES,Q*"

    val PART_CODE_TO_MODEL = FurunoProtocol.PART_CODE_TO_MODEL
}
