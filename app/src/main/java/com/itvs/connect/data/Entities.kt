package com.itvs.connect.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val distanceKm: Double,
    val startOdometerKm: Double?,
    val endOdometerKm: Double?,
    val startFuelPercent: Int?,
    val endFuelPercent: Int?,
    val clusterAfeKmL: Int?,
    val approxKmPerLitre: Double?,
    val estimatedLitresUsed: Double?,
    val economySource: String,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val startLat: Double?,
    val startLng: Double?,
    val endLat: Double?,
    val endLng: Double?,
    val routeJson: String,
    val notes: String = ""
)

@Entity(tableName = "parked_locations")
data class ParkedLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val isManual: Boolean,
    val label: String = ""
)

@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val speedKmh: Float,
    val timestampMs: Long
)
