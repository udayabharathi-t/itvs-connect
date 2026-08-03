package com.itvs.connect

import com.google.common.truth.Truth.assertThat
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
            clusterAfe = 45
        )
        assertThat(kmL).isWithin(0.1).of(40.0)
        assertThat(source).isEqualTo(RideStatsCalculator.EconomySource.FUEL_DELTA)
    }

    @Test
    fun fallsBackToClusterAfeWhenFuelUnchanged() {
        val metrics = RideStatsCalculator.compute(
            startOdo = 10.0,
            endOdo = 25.0,
            gpsDistanceKm = 0.0,
            startTimeMs = 0L,
            endTimeMs = 30 * 60 * 1000L,
            startFuelPercent = 60,
            endFuelPercent = 60,
            tankCapacityLitres = 5.1,
            clusterAfe = 48,
            maxSpeedKmh = 60.0
        )
        assertThat(metrics.distanceKm).isWithin(0.01).of(15.0)
        assertThat(metrics.approxKmPerLitre).isWithin(0.01).of(48.0)
        assertThat(metrics.economySource)
            .isEqualTo(RideStatsCalculator.EconomySource.CLUSTER_AFE)
        assertThat(metrics.avgSpeedKmh).isWithin(0.1).of(30.0)
    }
}
