package com.itvs.connect

import com.google.common.truth.Truth.assertThat
import com.itvs.connect.data.RideEntity
import com.itvs.connect.data.RideStatsCalculator
import org.junit.Test

class RideStatsCalculatorTest {

    @Test
    fun prefersOdometerDeltaOverGps() {
        val distance = RideStatsCalculator.distanceKm(
            startOdo = 100.0,
            endOdo = 112.5,
            gpsDistanceKm = 10.0
        )
        assertThat(distance).isWithin(0.01).of(12.5)
    }

    @Test
    fun estimatesLitresAndKmLFromFuelBars() {
        val litres = RideStatsCalculator.estimateLitresUsed(
            startFuelPercent = 80,
            endFuelPercent = 40,
            tankCapacityLitres = 5.0
        )
        assertThat(litres).isWithin(0.01).of(2.0)

        val (kmL, source) = RideStatsCalculator.approxKmPerLitre(
            distanceKm = 80.0,
            litresUsed = litres,
            avgClusterAfe = 45.0,
            lastClusterAfe = 45
        )
        assertThat(kmL).isWithin(0.1).of(40.0)
        assertThat(source).isEqualTo(RideStatsCalculator.EconomySource.FUEL_DELTA)
    }

    @Test
    fun averagesClusterAfeSamples() {
        val metrics = RideStatsCalculator.compute(
            startOdo = 10.0,
            endOdo = 25.0,
            gpsDistanceKm = 0.0,
            startTimeMs = 0L,
            endTimeMs = 30 * 60 * 1000L,
            startFuelPercent = 60,
            endFuelPercent = 60,
            tankCapacityLitres = 5.1,
            clusterAfe = 40,
            afeSamples = listOf(50, 46, 48),
            maxSpeedKmh = 60.0
        )
        assertThat(metrics.distanceKm).isWithin(0.01).of(15.0)
        assertThat(metrics.approxKmPerLitre).isWithin(0.01).of(48.0)
        assertThat(metrics.economySource)
            .isEqualTo(RideStatsCalculator.EconomySource.CLUSTER_AFE_AVG)
        assertThat(metrics.avgSpeedKmh).isWithin(0.1).of(30.0)
    }

    @Test
    fun mergeRidesCombinesDistanceAndTime() {
        val a = RideEntity(
            id = 1,
            startTimeMs = 1_000,
            endTimeMs = 61_000,
            durationMs = 60_000,
            distanceKm = 5.0,
            startOdometerKm = 10.0,
            endOdometerKm = 15.0,
            startFuelPercent = 80,
            endFuelPercent = 70,
            clusterAfeKmL = 45,
            approxKmPerLitre = 45.0,
            estimatedLitresUsed = 0.5,
            economySource = "FUEL_DELTA",
            avgSpeedKmh = 30.0,
            maxSpeedKmh = 40.0,
            startLat = 13.0,
            startLng = 80.0,
            endLat = 13.01,
            endLng = 80.01,
            routeJson = "[]"
        )
        val b = a.copy(
            id = 2,
            startTimeMs = 120_000,
            endTimeMs = 240_000,
            durationMs = 120_000,
            distanceKm = 10.0,
            startOdometerKm = 15.0,
            endOdometerKm = 25.0,
            endLat = 13.05,
            endLng = 80.05
        )
        val merged = RideStatsCalculator.mergeRides(listOf(a, b))
        assertThat(merged.distanceKm).isWithin(0.01).of(15.0)
        assertThat(merged.durationMs).isEqualTo(180_000)
        assertThat(merged.startTimeMs).isEqualTo(1_000)
        assertThat(merged.endTimeMs).isEqualTo(240_000)
        assertThat(merged.notes).contains("Merged")
    }
}
