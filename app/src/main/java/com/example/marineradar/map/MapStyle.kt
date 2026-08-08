package com.example.marineradar.map

/**
 * Kartfilter/kartstil som kan bytas direkt i kartbilden via lager-ikonen
 * (standardikonen för "kartlager"). Varje stil beskriver vad användaren
 * vill SE – respektive kartleverantör (osmdroid/Google) översätter sedan
 * stilen till sin egen motsvarighet, se [MapProviderType].
 *
 * Anledningen till att det här är ett eget enum istället för den gamla
 * boolean-flaggan "mörk stil" är att sjökort, satellit och terräng inte
 * går att uttrycka som på/av – och att fler stilar ska kunna läggas till
 * utan att ändra i varje vy.
 */
enum class MapStyle(val displayName: String, val icon: String) {
    /** Vanlig gatukarta, ljus. */
    STANDARD("Standard", "🗺"),
    /** Mörk gatukarta – skonsam för mörkerseende ombord. */
    DARK("Mörk", "🌙"),
    /** Flygfoto/satellit. */
    SATELLITE("Satellit", "🛰"),
    /** Höjdkurvor och terräng. */
    TERRAIN("Terräng", "⛰"),
    /** Natur/friluft – skog, stigar, vatten framhävt. */
    NATURE("Natur", "🌲"),
    /** Sjökortsöverlägg (OpenSeaMap): bojar, fyrar, farleder. */
    NAUTICAL("Sjökort", "⚓");

    companion object {
        fun fromName(name: String?): MapStyle =
            values().firstOrNull { it.name == name } ?: DARK
    }
}
