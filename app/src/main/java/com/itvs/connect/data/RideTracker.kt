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
    private val appContext = context.applicationContext
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val gson = Gson()
    private val placeNames = PlaceNameResolver(appContext)

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
    private val afeSamples = mutableListOf<Int>()
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
        val avgSpeedKmh: Double,
        val fuelPercent: Int?,
        val afe: Int?,
        /** Cluster AFE sample average — only when values actually varied. */
        val avgAfe: Double?,
        /** Trip economy from fuel-bar delta × tank capacity when calculable. */
        val tripKmPerLitre: Double?
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
        if (active && afe in 1..99) {
            // Keep a running sample stream for average km/L (cap memory).
            afeSamples += afe
            if (afeSamples.size > 2_000) {
                val compacted = afeSamples.filterIndexed { index, _ -> index % 2 == 0 }.toMutableList()
                afeSamples.clear()
                afeSamples.addAll(compacted)
            }
        }
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
        startOdo = odometerKm.takeIf { it > 0.0 }
        startFuel = fuelPercent.takeIf { it > 0 }
        latestOdo = startOdo
        latestFuel = startFuel
        latestAfe = afe.takeIf { it in 1..99 }
        maxSpeed = 0.0
        gpsDistanceM = 0.0
        lastLocation = null
        startLat = null
        startLng = null
        route.clear()
        afeSamples.clear()
        if (afe in 1..99) afeSamples += afe
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

        // Prefer a fresh high-accuracy fix for end/parked pin.
        val endFix = runCatching {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        }.getOrNull() ?: lastLocation
        if (endFix != null) {
            lastLocation = endFix
        }

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
            afeSamples = afeSamples.toList(),
            maxSpeedKmh = maxSpeed
        )

        // Ignore tiny ghost rides (ignition blips)
        if (metrics.distanceKm < 0.05 && metrics.durationMs < 60_000L) {
            _activeRide.value = null
            return null
        }

        val endLat = lastLocation?.latitude
        val endLng = lastLocation?.longitude

        // Resolve place names before insert so the first list paint already has labels.
        val startPlace = placeNames.resolve(startLat, startLng)
        val endPlace = placeNames.resolve(endLat, endLng)

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
            endLat = endLat,
            endLng = endLng,
            routeJson = gson.toJson(route),
            startPlaceName = startPlace,
            endPlaceName = endPlace
        )
        val id = database.rideDao().insert(entity)
        _activeRide.value = null

        if (endLat != null && endLng != null) {
            saveSingleParkedLocation(endLat, endLng, isManual = false, placeName = endPlace)
        }
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
        val durationMs = now - startTimeMs
        val litres = RideStatsCalculator.estimateLitresUsed(
            startFuelPercent = startFuel,
            endFuelPercent = latestFuel,
            tankCapacityLitres = tankCapacity
        )
        val tripKmL = if (litres != null && litres > 0.05 && distance > 0.05) {
            distance / litres
        } else {
            null
        }
        // Cluster AFE is often a sticky single reading (e.g. always 40). Only treat
        // sample average as meaningful when the cluster actually changed values.
        val distinctAfe = afeSamples.filter { it in 1..99 }.toSet()
        val avgAfe = if (distinctAfe.size >= 2) {
            RideStatsCalculator.averageAfe(afeSamples)
        } else {
            null
        }
        _activeRide.value = ActiveRideUi(
            startedAtMs = startTimeMs,
            distanceKm = distance,
            durationMs = durationMs,
            currentSpeedKmh = lastLocation?.let {
                if (it.hasSpeed()) it.speed * 3.6 else 0.0
            } ?: 0.0,
            avgSpeedKmh = RideStatsCalculator.averageSpeedKmh(distance, durationMs),
            fuelPercent = latestFuel,
            afe = latestAfe,
            avgAfe = avgAfe,
            tripKmPerLitre = tripKmL
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun captureParkedLocation(isManual: Boolean = false): ParkedLocationEntity? {
        val loc = runCatching {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                ?: fused.lastLocation.await()
        }.getOrNull() ?: lastLocation ?: return null
        val place = placeNames.resolve(loc.latitude, loc.longitude)
        return saveSingleParkedLocation(loc.latitude, loc.longitude, isManual, place)
    }

    /**
     * Fills missing start/end place names for an existing ride (e.g. older records).
     */
    suspend fun enrichPlaceNames(rideId: Long): RideEntity? {
        val ride = database.rideDao().getById(rideId) ?: return null
        val needStart = ride.startPlaceName.isNullOrBlank() &&
            ride.startLat != null && ride.startLng != null
        val needEnd = ride.endPlaceName.isNullOrBlank() &&
            ride.endLat != null && ride.endLng != null
        if (!needStart && !needEnd) return ride
        val start = if (needStart) placeNames.resolve(ride.startLat, ride.startLng)
        else ride.startPlaceName
        val end = if (needEnd) placeNames.resolve(ride.endLat, ride.endLng)
        else ride.endPlaceName
        database.rideDao().updatePlaceNames(rideId, start, end)
        return ride.copy(startPlaceName = start, endPlaceName = end)
    }

    private suspend fun saveSingleParkedLocation(
        lat: Double,
        lng: Double,
        isManual: Boolean,
        placeName: String? = null
    ): ParkedLocationEntity {
        // Keep only one parked pin (latest end location).
        database.parkedLocationDao().clearAll()
        val resolved = placeName ?: placeNames.resolve(lat, lng)
        val entity = ParkedLocationEntity(
            latitude = lat,
            longitude = lng,
            timestampMs = System.currentTimeMillis(),
            isManual = isManual,
            label = resolved?.takeIf { it.isNotBlank() }
                ?: if (isManual) "Manual" else "Last parked"
        )
        val id = database.parkedLocationDao().insert(entity)
        return entity.copy(id = id)
    }
}
