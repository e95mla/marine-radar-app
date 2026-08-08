package com.example.marineradar.settings

import android.content.Context

/**
 * Sparar ALLA inställningar (se [RadarSettings]) mellan sessioner med
 * SharedPreferences – inga extra beroenden, allt ligger lokalt på
 * telefonen.
 *
 * OBS: SharedPreferences lagras i klartext på disk. Det är okej för det
 * här bruket (ditt eget WiFi-lösenord till din egen radar, lokalt på din
 * egen telefon), men om appen någonsin ska dela enhet med andra bör
 * EncryptedSharedPreferences (androidx.security) användas istället.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("radar_settings", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_SSID = "DRS4W05619771"
        const val DEFAULT_PASSWORD = "16720hhb"
        const val DEFAULT_RADAR_OPACITY = 0.6f
    }

    /** Läser hela inställningsobjektet; saknade nycklar faller tillbaka på standardvärdet. */
    fun load(): RadarSettings {
        val d = RadarSettings()
        return RadarSettings(
            ssid = prefs.getString("wifi_ssid", d.ssid) ?: d.ssid,
            password = prefs.getString("wifi_password", d.password) ?: d.password,
            autoTransmitOnConnect = prefs.getBoolean("auto_transmit", d.autoTransmitOnConnect),
            northUp = prefs.getBoolean("north_up", d.northUp),
            showRangeRings = prefs.getBoolean("show_rings", d.showRangeRings),
            showHeadingLine = prefs.getBoolean("show_heading_line", d.showHeadingLine),
            showDataBar = prefs.getBoolean("show_data_bar", d.showDataBar),
            showTargetList = prefs.getBoolean("show_target_list", d.showTargetList),
            keepScreenOn = prefs.getBoolean("keep_screen_on", d.keepScreenOn),
            targetTrackingEnabled = prefs.getBoolean("target_tracking", d.targetTrackingEnabled),
            detectThreshold = prefs.getInt("detect_threshold", d.detectThreshold),
            showTrails = prefs.getBoolean("show_trails", d.showTrails),
            vectorMinutes = prefs.getInt("vector_minutes", d.vectorMinutes),
            cpaAlarmEnabled = prefs.getBoolean("cpa_alarm", d.cpaAlarmEnabled),
            cpaLimitNm = prefs.getFloat("cpa_limit_nm", d.cpaLimitNm),
            tcpaLimitMinutes = prefs.getFloat("tcpa_limit_min", d.tcpaLimitMinutes),
            alarmSound = prefs.getBoolean("alarm_sound", d.alarmSound),
            alarmVibrate = prefs.getBoolean("alarm_vibrate", d.alarmVibrate),
            guardEnabled = prefs.getBoolean("guard_enabled", d.guardEnabled),
            guardInnerNm = prefs.getFloat("guard_inner_nm", d.guardInnerNm),
            guardOuterNm = prefs.getFloat("guard_outer_nm", d.guardOuterNm),
            guardStartDeg = prefs.getFloat("guard_start_deg", d.guardStartDeg),
            guardWidthDeg = prefs.getFloat("guard_width_deg", d.guardWidthDeg),
            mapProviderName = prefs.getString("map_provider", d.mapProviderName) ?: d.mapProviderName,
            radarOpacity = prefs.getFloat("radar_opacity", d.radarOpacity),
            // Migrering: tidigare fanns bara en boolean "mörk stil".
            mapStyleName = prefs.getString(
                "map_style",
                if (prefs.getBoolean("map_dark_style", true)) "DARK" else "STANDARD"
            ) ?: d.mapStyleName,
            verboseLogging = prefs.getBoolean("verbose_logging", d.verboseLogging)
        )
    }

    /** Skriver hela inställningsobjektet. Anropas vid varje ändring i inställningsvyn. */
    fun save(s: RadarSettings) {
        prefs.edit()
            .putString("wifi_ssid", s.ssid)
            .putString("wifi_password", s.password)
            .putBoolean("auto_transmit", s.autoTransmitOnConnect)
            .putBoolean("north_up", s.northUp)
            .putBoolean("show_rings", s.showRangeRings)
            .putBoolean("show_heading_line", s.showHeadingLine)
            .putBoolean("show_data_bar", s.showDataBar)
            .putBoolean("show_target_list", s.showTargetList)
            .putBoolean("keep_screen_on", s.keepScreenOn)
            .putBoolean("target_tracking", s.targetTrackingEnabled)
            .putInt("detect_threshold", s.detectThreshold)
            .putBoolean("show_trails", s.showTrails)
            .putInt("vector_minutes", s.vectorMinutes)
            .putBoolean("cpa_alarm", s.cpaAlarmEnabled)
            .putFloat("cpa_limit_nm", s.cpaLimitNm)
            .putFloat("tcpa_limit_min", s.tcpaLimitMinutes)
            .putBoolean("alarm_sound", s.alarmSound)
            .putBoolean("alarm_vibrate", s.alarmVibrate)
            .putBoolean("guard_enabled", s.guardEnabled)
            .putFloat("guard_inner_nm", s.guardInnerNm)
            .putFloat("guard_outer_nm", s.guardOuterNm)
            .putFloat("guard_start_deg", s.guardStartDeg)
            .putFloat("guard_width_deg", s.guardWidthDeg)
            .putString("map_provider", s.mapProviderName)
            .putFloat("radar_opacity", s.radarOpacity)
            .putString("map_style", s.mapStyleName)
            .putBoolean("verbose_logging", s.verboseLogging)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
