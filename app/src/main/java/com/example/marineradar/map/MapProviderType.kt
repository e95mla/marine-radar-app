package com.example.marineradar.map

/**
 * Valbara kartleverantörer för karta+radar-överlägget.
 *
 * - [OPENSTREETMAP]: helt gratis, kräver ingen API-nyckel, fungerar
 *   direkt. Standardval av just den anledningen.
 * - [GOOGLE_MAPS]: kräver en gratis Google Maps API-nyckel (se
 *   README.md för hur du skaffar en). Har satellitvy som osmdroid
 *   saknar utan extra tilläggstjänst.
 */
enum class MapProviderType(val displayName: String) {
    OPENSTREETMAP("OpenStreetMap (gratis)"),
    GOOGLE_MAPS("Google Maps (kräver API-nyckel)")
}
