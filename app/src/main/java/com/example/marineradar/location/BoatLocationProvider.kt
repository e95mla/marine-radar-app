package com.example.marineradar.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.example.marineradar.debug.FileLogger
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ger båtens GPS-position och kompasskurs, så radarbilden kan placeras
 * och roteras korrekt ovanpå en karta. DRS4W själv skickar ingen
 * position/kurs (den är bara en radarsensor) – det måste komma från
 * telefonens egna GPS + magnetometer/rörelsesensorer.
 *
 * Använder plain [LocationManager] (inte Fused Location) för att slippa
 * ett extra Google Play Services-beroende utöver kartan.
 */
class BoatLocationProvider(private val context: Context) : SensorEventListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _location = MutableStateFlow<LatLng?>(null)
    val location: StateFlow<LatLng?> = _location.asStateFlow()

    /**
     * Kursen som resten av appen ska använda, 0 = norr, medurs.
     *
     * Ombord är det BÅTENS färdriktning som gäller, inte åt vilket håll
     * telefonen råkar hållas. Så snart GPS:en rapporterar en kurs över grund
     * med fart över [GPS_HEADING_MIN_MS] används den; står båten stilla (då
     * är GPS-kursen bara brus) faller vi tillbaka på telefonens kompass.
     */
    private val _headingDegrees = MutableStateFlow(0f)
    val headingDegrees: StateFlow<Float> = _headingDegrees.asStateFlow()

    /** Senaste kompassvärdet, används bara när GPS-kurs saknas. */
    private var compassHeading = 0f

    /** true när [headingDegrees] kommer från GPS-kursen (båtens riktning). */
    private val _headingFromGps = MutableStateFlow(false)
    val headingFromGps: StateFlow<Boolean> = _headingFromGps.asStateFlow()

    private var started = false

    /** Fart över grund i knop (från GPS), null tills en fix med fart finns. */
    private val _speedKnots = MutableStateFlow<Float?>(null)
    val speedKnots: StateFlow<Float?> = _speedKnots.asStateFlow()

    /** Kurs över grund i grader (från GPS), null när båten står stilla. */
    private val _courseOverGround = MutableStateFlow<Float?>(null)
    val courseOverGround: StateFlow<Float?> = _courseOverGround.asStateFlow()

    private val locationListener = LocationListener { loc: Location ->
        _location.value = LatLng(loc.latitude, loc.longitude)
        if (loc.hasSpeed()) _speedKnots.value = loc.speed * 1.94384f
        // GPS-bäring är bara meningsfull när man faktiskt rör sig.
        if (loc.hasBearing() && loc.speed > GPS_HEADING_MIN_MS) {
            _courseOverGround.value = loc.bearing
            lastCourseAt = System.currentTimeMillis()
        }
        updateHeading()
    }

    /** Tidpunkt för senaste giltiga GPS-kurs, så en gammal kurs inte fastnar. */
    private var lastCourseAt = 0L

    private fun updateHeading() {
        val cog = _courseOverGround.value
        val fresh = cog != null && System.currentTimeMillis() - lastCourseAt < COURSE_MAX_AGE_MS
        _headingFromGps.value = fresh
        _headingDegrees.value = if (fresh) cog!! else compassHeading
    }

    @SuppressLint("MissingPermission") // behörighet kontrolleras/begärs redan i MainActivity
    fun start() {
        if (started) return
        started = true
        try {
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                FileLogger.log("WARN", "BoatLocationProvider: ingen platstjänst är påslagen")
            } else {
                locationManager.requestLocationUpdates(provider, 1000L, 1f, locationListener)
                locationManager.getLastKnownLocation(provider)?.let {
                    _location.value = LatLng(it.latitude, it.longitude)
                }
                FileLogger.log("INFO", "BoatLocationProvider: startad med provider=$provider")
            }
        } catch (e: SecurityException) {
            FileLogger.log("ERROR", "BoatLocationProvider: saknar platsbehörighet", e)
        } catch (e: Exception) {
            FileLogger.log("ERROR", "BoatLocationProvider: kunde inte starta GPS", e)
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            FileLogger.log("WARN", "BoatLocationProvider: ingen rotations-/kompasssensor tillgänglig på enheten")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) { }
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        try {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuthDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            compassHeading = (azimuthDegrees + 360f) % 360f
            updateHeading()
        } catch (_: Exception) {
            // Ignorera enstaka trasiga sensoravläsningar.
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** ~0,6 knop – under det är GPS-kursen inte tillförlitlig. */
        const val GPS_HEADING_MIN_MS = 0.3f
        /** Hur länge en GPS-kurs får användas efter senaste fix. */
        const val COURSE_MAX_AGE_MS = 10_000L
    }
}
