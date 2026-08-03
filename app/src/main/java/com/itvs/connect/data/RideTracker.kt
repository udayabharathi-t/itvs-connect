package com.itvs.connect.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Auto-tracks rides when scooter ignition telemetry becomes active.
 * Stores completed rides locally in Room — no backend / login.
 */
class RideTracker(
    context: Context,
    private val database: AppDatabase,
    private val preferences: PreferencesRepository
) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val gson = Gson()

    private var active = false
    private var startTimeMs = 0L
    private var startOdo: Double? = null
    private var startFuel: Int? = null
    private var latestOdo: Double? = null
    private var latestFuel: Int? = null
    private var latestAfe: Int? = null
    private var maxSpeed = 0.0
    private var gpsDistanceM = 0.0
    private var lastLocation: Location? = null
    private var startLat: Double? = null
    private var startLng: Double? = null
    private val route = mutableListOf<RoutePoint>()
    private var tankCapacity = 5.1

    private val _activeRide = MutableStateFlow<ActiveRideUi?>(null)
    val activeRide: StateFlow<ActiveRideUi?> = _activeRide.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            onLocation(loc)
        }
    }

    data class ActiveRideUi(
        val startedAtMs: Long,
        val distanceKm: Double,
        val durationMs: Long,
        val currentSpeedKmh: Double,
        val fuelPercent: Int?,
        val afe: Int?
    )

    fun onTelemetry(
        odometerKm: Double,
        fuelPercent: Int,
        afe: Int,
        tankCapacityLitres: Double
    ) {
        tankCapacity = tankCapacityLitres
        latestOdo = odometerKm
        latestFuel = fuelPercent
        latestAfe = afe
        if (active) {
            publishActive()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun startRideIfNeeded(
        odometerKm: Double,
        fuelPercent: Int,
        afe: Int,
        tankCapacityLitres: Double
    ) {
        if (active) {
            onTelemetry(odometerKm, fuelPercent, afe, tankCapacityLitres)
            return
        }
        active = true
        tankCapacity = tankCapacityLitres
        startTimeMs = System.currentTimeMillis()
        startOdo = odometerKm
        startFuel = fuelPercent
        latestOdo = odometerKm
        latestFuel = fuelPercent
        latestAfe = afe
        maxSpeed = 0.0
        gpsDistanceM = 0.0
        lastLocation = null
        route.clear()
        preferences.setRideMode(true)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(3f)
            .build()
        runCatching {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            fused.lastLocation.await()?.let { onLocation(it) }
        }
        publishActive()
    }

    suspend fun endRideIfNeeded(): RideEntity? {
        if (!active) return null
        active = false
        runCatching { fused.removeLocationUpdates(locationCallback) }
        preferences.setRideMode(false)

        val endTime = System.currentTimeMillis()
        val metrics = RideStatsCalculator.compute(
            startOdo = startOdo,
            endOdo = latestOdo,
            gpsDistanceKm = gpsDistanceM / 1000.0,
            startTimeMs = startTimeMs,
            endTimeMs = endTime,
            startFuelPercent = startFuel,
            endFuelPercent = latestFuel,
            tankCapacityLitres = tankCapacity,
            clusterAfe = latestAfe,
            maxSpeedKmh = maxSpeed
        )

        // Ignore tiny ghost rides (ignition blips)
        if (metrics.distanceKm < 0.05 && metrics.durationMs < 60_000L) {
            _activeRide.value = null
            return null
        }

        val entity = RideEntity(
            startTimeMs = startTimeMs,
            endTimeMs = endTime,
            durationMs = metrics.durationMs,
            distanceKm = metrics.distanceKm,
            startOdometerKm = startOdo,
            endOdometerKm = latestOdo,
            startFuelPercent = startFuel,
            endFuelPercent = latestFuel,
            clusterAfeKmL = latestAfe,
            approxKmPerLitre = metrics.approxKmPerLitre,
            estimatedLitresUsed = metrics.estimatedLitresUsed,
            economySource = metrics.economySource.name,
            avgSpeedKmh = metrics.avgSpeedKmh,
            maxSpeedKmh = maxSpeed,
            startLat = startLat,
            startLng = startLng,
            endLat = lastLocation?.latitude,
            endLng = lastLocation?.longitude,
            routeJson = gson.toJson(route)
        )
        val id = database.rideDao().insert(entity)
        _activeRide.value = null
        return entity.copy(id = id)
    }

    fun isActive(): Boolean = active

    private fun onLocation(loc: Location) {
        if (!active) return
        if (startLat == null) {
            startLat = loc.latitude
            startLng = loc.longitude
        }
        lastLocation?.let { prev ->
            val d = prev.distanceTo(loc)
            if (d in 1f..200f) {
                gpsDistanceM += d
            }
        }
        lastLocation = loc
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        if (speedKmh > maxSpeed) maxSpeed = speedKmh
        route += RoutePoint(loc.latitude, loc.longitude, speedKmh.toFloat(), loc.time)
        // Cap route samples to keep local DB light
        if (route.size > 5_000) {
            val compacted = route.filterIndexed { index, _ -> index % 2 == 0 }.toMutableList()
            route.clear()
            route.addAll(compacted)
        }
        publishActive()
    }

    private fun publishActive() {
        if (!active) return
        val now = System.currentTimeMillis()
        val distance = RideStatsCalculator.distanceKm(
            startOdo,
            latestOdo,
            gpsDistanceM / 1000.0
        )
        _activeRide.value = ActiveRideUi(
            startedAtMs = startTimeMs,
            distanceKm = distance,
            durationMs = now - startTimeMs,
            currentSpeedKmh = lastLocation?.let {
                if (it.hasSpeed()) it.speed * 3.6 else 0.0
            } ?: 0.0,
            fuelPercent = latestFuel,
            afe = latestAfe
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun captureParkedLocation(isManual: Boolean = false): ParkedLocationEntity? {
        val loc = runCatching { fused.lastLocation.await() }.getOrNull() ?: return null
        val entity = ParkedLocationEntity(
            latitude = loc.latitude,
            longitude = loc.longitude,
            timestampMs = System.currentTimeMillis(),
            isManual = isManual
        )
        database.parkedLocationDao().insert(entity)
        database.parkedLocationDao().trim(50)
        return entity
    }
}
