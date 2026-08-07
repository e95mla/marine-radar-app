package com.example.marineradar.settings

import android.content.Context

/**
 * Sparar WiFi-uppgifter mellan sessioner med SharedPreferences (enkelt,
 * inga extra beroenden). Uppgifterna lagras endast lokalt på telefonen.
 *
 * OBS: SharedPreferences lagras i klartext på disk. Det är okej för det
 * här bruket (ditt eget WiFi-lösenord till din egen radar, lokalt på din
 * egen telefon), men om appen någonsin ska dela enhet med andra bör
 * EncryptedSharedPreferences (androidx.security) användas istället.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("radar_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SSID = "wifi_ssid"
        private const val KEY_PASSWORD = "wifi_password"
        private const val KEY_MAP_PROVIDER = "map_provider"
        private const val KEY_RADAR_OPACITY = "radar_opacity"
        private const val KEY_MAP_DARK_STYLE = "map_dark_style"

        // Förifyllda standardvärden tills användaren sparar egna.
        const val DEFAULT_SSID = "DRS4W05619771"
        const val DEFAULT_PASSWORD = "16720hhb"
        const val DEFAULT_RADAR_OPACITY = 0.6f
    }

    fun getSsid(): String = prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID

    fun getPassword(): String = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD

    fun save(ssid: String, password: String) {
        prefs.edit()
            .putString(KEY_SSID, ssid)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    /** Namnet ("OPENSTREETMAP"/"GOOGLE_MAPS") på senast valda kartleverantör, eller null om inget sparat än. */
    fun getMapProviderName(): String? = prefs.getString(KEY_MAP_PROVIDER, null)

    fun saveMapProviderName(name: String) {
        prefs.edit().putString(KEY_MAP_PROVIDER, name).apply()
    }

    fun getRadarOpacity(): Float = prefs.getFloat(KEY_RADAR_OPACITY, DEFAULT_RADAR_OPACITY)

    fun saveRadarOpacity(value: Float) {
        prefs.edit().putFloat(KEY_RADAR_OPACITY, value).apply()
    }

    /** true = mörk kartstil (standard), false = ljus. */
    fun getMapDarkStyle(): Boolean = prefs.getBoolean(KEY_MAP_DARK_STYLE, true)

    fun saveMapDarkStyle(dark: Boolean) {
        prefs.edit().putBoolean(KEY_MAP_DARK_STYLE, dark).apply()
    }
}
