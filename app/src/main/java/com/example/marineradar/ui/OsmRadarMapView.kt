package com.example.marineradar.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.marineradar.radar.PpiRenderer
import com.google.android.gms.maps.model.LatLng
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos

/**
 * Kartkälla: Carto (basemaps.cartocdn.com), inte OpenStreetMaps EGNA
 * kaklingsservrar (tile.openstreetmap.org). OSM:s egna servrar blockerar
 * (HTTP 403 "Access blocked") appar med generiska/exempel-paketnamn som
 * `com.example.*` för att skydda sina frivilligdrivna resurser mot
 * missbruk – se osmwiki.org/Blocked. Carto tillhandahåller samma
 * OSM-baserade kartdata via sin egen, mer permissiva gratis-CDN, byggd
 * just för den här typen av klientanvändning. Fungerar direkt utan
 * API-nyckel eller vidare konfiguration.
 */
private val CARTO_DARK = XYTileSource(
    "CartoDBDarkMatter",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    ),
    "© OpenStreetMap contributors © CARTO"
)

/** Ljusare kartstil, samma leverantör – valbar i Karta-panelen. */
private val CARTO_LIGHT = XYTileSource(
    "CartoDBVoyager",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
    ),
    "© OpenStreetMap contributors © CARTO"
)

/**
 * OpenStreetMap-baserad karta via osmdroid – helt gratis, ingen
 * API-nyckel behövs. Ritar radarbilden som ett eget [Overlay] som
 * positioneras/roteras/skalas manuellt utifrån båtens GPS-position,
 * kompasskurs och radarns räckvidd (osmdroid har ingen inbyggd
 * "GroundOverlay" som Google Maps, så vi implementerar motsvarande
 * själva via [Projection] för att omvandla lat/long till skärmpixlar).
 */
@Composable
fun OsmRadarMapView(
    renderer: PpiRenderer?,
    boatLocation: LatLng?,
    headingDegrees: Float,
    rangeMeters: Int,
    opacity: Float = 0.6f,
    darkStyle: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val radarOverlay = remember { RadarOverlay() }
    radarOverlay.update(renderer, boatLocation, headingDegrees, rangeMeters, opacity)

    val mapView = remember {
        // Beskrivande User-Agent istället för det generiska paketnamnet
        // – rekommenderas av OSM/Carto för att identifiera appen korrekt.
        Configuration.getInstance().userAgentValue = "MarineRadarApp/1.0"
        Configuration.getInstance().osmdroidTileCache = context.cacheDir
        MapView(context).apply {
            setTileSource(if (darkStyle) CARTO_DARK else CARTO_LIGHT)
            setMultiTouchControls(true)
            // Inbyggda +/- zoomknappar avstängda – de hamnade bakom/
            // krockade med kontrollpanelen längst ner. Pinch-to-zoom
            // (setMultiTouchControls ovan) räcker för att zooma.
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
            overlays.add(radarOverlay)
            overlays.add(AttributionOverlay())
        }
    }

    DisposableEffect(darkStyle) {
        mapView.setTileSource(if (darkStyle) CARTO_DARK else CARTO_LIGHT)
        onDispose { }
    }

    DisposableEffect(boatLocation) {
        boatLocation?.let {
            mapView.controller.setCenter(GeoPoint(it.latitude, it.longitude))
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
        }
    }

    // clipToBounds() är kritiskt här: osmdroids MapView kan annars rendera
    // (och ta emot touch-events för) ett större område än det Compose
    // faktiskt tilldelat den, vilket gjorde att kartan täckte/blockerade
    // resten av UI:t (gick inte att lämna kartläget, zoom fungerade inte).
    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
        factory = { mapView },
        update = { it.invalidate() }
    )
}

/**
 * Ritar radarbilden centrerad på båtens position, roterad efter
 * kompasskurs och skalad så att bildens radie motsvarar radarns
 * inställda räckvidd i meter – motsvarande Google Maps' GroundOverlay,
 * men implementerat manuellt eftersom osmdroid saknar en färdig
 * variant av det.
 */
private class RadarOverlay : Overlay() {

    private var renderer: PpiRenderer? = null
    private var boatLocation: LatLng? = null
    private var headingDegrees: Float = 0f
    private var rangeMeters: Int = 1000
    private var opacity: Float = 0.6f
    private val paint = Paint().apply { isAntiAlias = true }

    fun update(renderer: PpiRenderer?, boatLocation: LatLng?, headingDegrees: Float, rangeMeters: Int, opacity: Float) {
        this.renderer = renderer
        this.boatLocation = boatLocation
        this.headingDegrees = headingDegrees
        this.rangeMeters = rangeMeters
        this.opacity = opacity
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loc = boatLocation ?: return
        val bmp = renderer?.bitmap ?: return
        if (bmp.width == 0) return

        try {
            val projection: Projection = mapView.projection
            val geoPoint = GeoPoint(loc.latitude, loc.longitude)
            val centerPoint = projection.toPixels(geoPoint, null)

            // Pixlar per meter vid ekvatorn för nuvarande zoom, justerat
            // för latitud (Mercator-projektionen "sträcker ut" öst-väst-
            // skalan längre bort från ekvatorn).
            val metersPerPixelAtEquator = 1.0 / projection.metersToEquatorPixels(1f)
            val latitudeCorrection = cos(Math.toRadians(loc.latitude)).coerceAtLeast(0.01)
            val metersPerPixel = metersPerPixelAtEquator / latitudeCorrection
            val radiusPx = (rangeMeters / metersPerPixel).toFloat()
            if (radiusPx <= 0f || radiusPx.isNaN() || radiusPx.isInfinite()) return

            val scale = (radiusPx * 2f) / bmp.width

            paint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
            canvas.save()
            canvas.translate(centerPoint.x.toFloat(), centerPoint.y.toFloat())
            canvas.rotate(headingDegrees)
            canvas.scale(scale, scale)
            canvas.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, paint)
            canvas.restore()
        } catch (_: Exception) {
            // Rita aldrig sönder kartan pga ett enstaka beräkningsfel.
        }
    }
}

/** Liten obligatorisk "© OpenStreetMap contributors"-attribution längst ner till vänster. */
private class AttributionOverlay : Overlay() {
    private val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 24f
        isAntiAlias = true
        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        canvas.drawText("© OpenStreetMap contributors", 12f, mapView.height - 12f, paint)
    }

    override fun onTouchEvent(e: MotionEvent?, mapView: MapView?) = false
}
