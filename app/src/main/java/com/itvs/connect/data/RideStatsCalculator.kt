package com.itvs.connect.data

/**
 * Pure helpers for ride distance / economy math.
 *
 * Approx km/L prefers fuel-bar delta when tank capacity is known.
 * Otherwise uses the average of cluster AFE samples collected during the ride,
 * falling back to the last cluster AFE reading.
 */
object RideStatsCalculator {

    data class RideMetrics(
        val distanceKm: Double,
        val durationMs: Long,
        val avgSpeedKmh: Double,
        val approxKmPerLitre: Double?,
        val estimatedLitresUsed: Double?,
        val economySource: EconomySource,
        val avgClusterAfeKmL: Double?
    )

    enum class EconomySource {
        FUEL_DELTA,
        CLUSTER_AFE_AVG,
        CLUSTER_AFE,
        UNKNOWN
    }

    fun distanceKm(startOdo: Double?, endOdo: Double?, gpsDistanceKm: Double): Double {
        val odoDelta = if (startOdo != null && endOdo != null && endOdo >= startOdo) {
            endOdo - startOdo
        } else {
            null
        }
        return when {
            odoDelta != null && odoDelta > 0.01 -> odoDelta
            gpsDistanceKm > 0.01 -> gpsDistanceKm
            else -> odoDelta ?: 0.0
        }
    }

    fun averageSpeedKmh(distanceKm: Double, durationMs: Long): Double {
        if (durationMs <= 0L || distanceKm <= 0.0) return 0.0
        val hours = durationMs / 3_600_000.0
        return distanceKm / hours
    }

    fun estimateLitresUsed(
        startFuelPercent: Int?,
        endFuelPercent: Int?,
        tankCapacityLitres: Double
    ): Double? {
        if (startFuelPercent == null || endFuelPercent == null) return null
        if (tankCapacityLitres <= 0.0) return null
        val deltaPercent = startFuelPercent - endFuelPercent
        if (deltaPercent <= 0) return null
        return (deltaPercent / 100.0) * tankCapacityLitres
    }

    fun averageAfe(samples: List<Int>): Double? {
        val valid = samples.filter { it in 1..99 }
        if (valid.isEmpty()) return null
        return valid.average()
    }

    fun approxKmPerLitre(
        distanceKm: Double,
        litresUsed: Double?,
        avgClusterAfe: Double?,
        lastClusterAfe: Int?
    ): Pair<Double?, EconomySource> {
        if (litresUsed != null && litresUsed > 0.05 && distanceKm > 0.05) {
            return (distanceKm / litresUsed) to EconomySource.FUEL_DELTA
        }
        if (avgClusterAfe != null && avgClusterAfe > 0.0 && distanceKm > 0.05) {
            return avgClusterAfe to EconomySource.CLUSTER_AFE_AVG
        }
        if (lastClusterAfe != null && lastClusterAfe in 1..99 && distanceKm > 0.05) {
            return lastClusterAfe.toDouble() to EconomySource.CLUSTER_AFE
        }
        return null to EconomySource.UNKNOWN
    }

    fun compute(
        startOdo: Double?,
        endOdo: Double?,
        gpsDistanceKm: Double,
        startTimeMs: Long,
        endTimeMs: Long,
        startFuelPercent: Int?,
        endFuelPercent: Int?,
        tankCapacityLitres: Double,
        clusterAfe: Int?,
        afeSamples: List<Int> = emptyList(),
        maxSpeedKmh: Double = 0.0
    ): RideMetrics {
        val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(0L)
        val distance = distanceKm(startOdo, endOdo, gpsDistanceKm)
        val litres = estimateLitresUsed(startFuelPercent, endFuelPercent, tankCapacityLitres)
        val avgAfe = averageAfe(afeSamples)
        val (kmL, source) = approxKmPerLitre(distance, litres, avgAfe, clusterAfe)
        @Suppress("UNUSED_VARIABLE")
        val ignored = maxSpeedKmh
        return RideMetrics(
            distanceKm = distance,
            durationMs = durationMs,
            avgSpeedKmh = averageSpeedKmh(distance, durationMs),
            approxKmPerLitre = kmL,
            estimatedLitresUsed = litres,
            economySource = source,
            avgClusterAfeKmL = avgAfe
        )
    }

    /** Merge multiple rides into one aggregate (chronological). */
    fun mergeRides(rides: List<RideEntity>): RideEntity {
        require(rides.size >= 2) { "Need at least 2 rides to merge" }
        val ordered = rides.sortedBy { it.startTimeMs }
        val first = ordered.first()
        val last = ordered.last()
        val distance = ordered.sumOf { it.distanceKm }
        val duration = ordered.sumOf { it.durationMs }
        val litres = ordered.mapNotNull { it.estimatedLitresUsed }.takeIf { it.isNotEmpty() }?.sum()
        val afeSamples = ordered.mapNotNull { it.approxKmPerLitre?.toInt() }
        val avgAfe = ordered.mapNotNull { it.approxKmPerLitre }.takeIf { it.isNotEmpty() }?.average()
        val (kmL, source) = approxKmPerLitre(
            distanceKm = distance,
            litresUsed = litres,
            avgClusterAfe = avgAfe,
            lastClusterAfe = last.clusterAfeKmL
        )
        return RideEntity(
            startTimeMs = first.startTimeMs,
            endTimeMs = last.endTimeMs,
            durationMs = duration,
            distanceKm = distance,
            startOdometerKm = first.startOdometerKm,
            endOdometerKm = last.endOdometerKm,
            startFuelPercent = first.startFuelPercent,
            endFuelPercent = last.endFuelPercent,
            clusterAfeKmL = last.clusterAfeKmL,
            approxKmPerLitre = kmL,
            estimatedLitresUsed = litres,
            economySource = source.name,
            avgSpeedKmh = averageSpeedKmh(distance, duration),
            maxSpeedKmh = ordered.maxOf { it.maxSpeedKmh },
            startLat = first.startLat,
            startLng = first.startLng,
            endLat = last.endLat,
            endLng = last.endLng,
            routeJson = "[]",
            notes = "Merged from ${ordered.size} rides"
        )
    }

    fun sameLocation(
        lat1: Double?,
        lng1: Double?,
        lat2: Double?,
        lng2: Double?,
        thresholdMeters: Float = 40f
    ): Boolean {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) return false
        return haversineMeters(lat1, lng1, lat2, lng2) <= thresholdMeters
    }

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earth * c
    }
}
