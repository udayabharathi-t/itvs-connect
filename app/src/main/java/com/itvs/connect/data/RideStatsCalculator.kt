package com.itvs.connect.data

/**
 * Pure helpers for ride distance / economy math.
 *
 * Approx km/L prefers fuel-bar delta when tank capacity is known.
 * Falls back to the cluster-reported average fuel economy (AFE).
 */
object RideStatsCalculator {

    data class RideMetrics(
        val distanceKm: Double,
        val durationMs: Long,
        val avgSpeedKmh: Double,
        val approxKmPerLitre: Double?,
        val estimatedLitresUsed: Double?,
        val economySource: EconomySource
    )

    enum class EconomySource {
        FUEL_DELTA,
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

    fun approxKmPerLitre(
        distanceKm: Double,
        litresUsed: Double?,
        clusterAfe: Int?
    ): Pair<Double?, EconomySource> {
        if (litresUsed != null && litresUsed > 0.05 && distanceKm > 0.05) {
            return (distanceKm / litresUsed) to EconomySource.FUEL_DELTA
        }
        if (clusterAfe != null && clusterAfe in 1..99 && distanceKm > 0.05) {
            return clusterAfe.toDouble() to EconomySource.CLUSTER_AFE
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
        maxSpeedKmh: Double
    ): RideMetrics {
        val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(0L)
        val distance = distanceKm(startOdo, endOdo, gpsDistanceKm)
        val litres = estimateLitresUsed(startFuelPercent, endFuelPercent, tankCapacityLitres)
        val (kmL, source) = approxKmPerLitre(distance, litres, clusterAfe)
        return RideMetrics(
            distanceKm = distance,
            durationMs = durationMs,
            avgSpeedKmh = averageSpeedKmh(distance, durationMs),
            approxKmPerLitre = kmL,
            estimatedLitresUsed = litres,
            economySource = source
        ).also {
            // maxSpeed is tracked externally; kept for API completeness
            @Suppress("UNUSED_VARIABLE")
            val ignored = maxSpeedKmh
        }
    }
}
