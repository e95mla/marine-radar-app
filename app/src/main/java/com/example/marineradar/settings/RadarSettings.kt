package com.example.marineradar.settings

/**
 * Alla användarinställningar i appen, samlade i ETT oföränderligt objekt.
 *
 * Anledningen till att allt ligger här istället för som spridda fält i
 * [com.example.marineradar.radar.RadarViewModel] är att varje inställning
 * ska (a) gå att ändra från en enda vy, och (b) automatiskt sparas mellan
 * sessioner – se [SettingsStore.save]. Lägg till nya inställningar här och
 * i SettingsStore, så följer resten med av sig självt.
 */
data class RadarSettings(
    // --- Anslutning -------------------------------------------------
    val ssid: String = SettingsStore.DEFAULT_SSID,
    val password: String = SettingsStore.DEFAULT_PASSWORD,
    /** Begär TRANSMIT automatiskt så fort kommandokanalen är uppe. */
    val autoTransmitOnConnect: Boolean = false,

    // --- Visning ----------------------------------------------------
    /** true = norr uppåt (bilden roteras med kompasskursen), false = fören uppåt. */
    val northUp: Boolean = false,
    val showRangeRings: Boolean = true,
    val showHeadingLine: Boolean = true,
    val showDataBar: Boolean = true,
    val showTargetList: Boolean = true,
    /** Håller skärmen tänd så länge radarn strömmar. */
    val keepScreenOn: Boolean = true,

    // --- Mål (MARPA-lite) -------------------------------------------
    val targetTrackingEnabled: Boolean = true,
    /** Ekostyrka 0-255 som krävs för att en cell ska räknas som mål. */
    val detectThreshold: Int = 110,
    /** Rita spårhistorik ("trails") bakom varje mål. */
    val showTrails: Boolean = true,
    /** Rita kursvektor motsvarande så här många minuters förflyttning. */
    val vectorMinutes: Int = 6,

    // --- Larm -------------------------------------------------------
    val cpaAlarmEnabled: Boolean = true,
    /** CPA-gräns i sjömil för kollisionsvarning. */
    val cpaLimitNm: Float = 0.25f,
    /** TCPA-gräns i minuter för kollisionsvarning. */
    val tcpaLimitMinutes: Float = 10f,
    val alarmSound: Boolean = true,
    val alarmVibrate: Boolean = true,

    // --- Vaktzon (guard zone) ---------------------------------------
    val guardEnabled: Boolean = false,
    val guardInnerNm: Float = 0.2f,
    val guardOuterNm: Float = 0.75f,
    /** Sektorns startbäring i grader (relativt bilden, 0 = uppåt). */
    val guardStartDeg: Float = 0f,
    /** Sektorns bredd i grader – 360 = hel ring. */
    val guardWidthDeg: Float = 360f,

    // --- Karta ------------------------------------------------------
    val mapProviderName: String = "OPENSTREETMAP",
    val radarOpacity: Float = SettingsStore.DEFAULT_RADAR_OPACITY,
    /** Kartfilter, se [com.example.marineradar.map.MapStyle]. */
    val mapStyleName: String = "DARK",

    // --- Felsökning -------------------------------------------------
    /** Loggar varje spoke-paket (mycket data – bara vid felsökning). */
    val verboseLogging: Boolean = false
) {
    val cpaLimitMeters: Float get() = cpaLimitNm * 1852f
    val tcpaLimitSeconds: Float get() = tcpaLimitMinutes * 60f
    val guardInnerMeters: Float get() = guardInnerNm * 1852f
    val guardOuterMeters: Float get() = guardOuterNm * 1852f
}
