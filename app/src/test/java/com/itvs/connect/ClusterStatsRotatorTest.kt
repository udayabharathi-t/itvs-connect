package com.itvs.connect

import com.google.common.truth.Truth.assertThat
import com.itvs.connect.ble.ClusterStatsRotator
import com.itvs.connect.ble.MapsNavParser
import com.itvs.connect.ble.MapsNavSnapshot
import com.itvs.connect.ble.PacketBuilder
import org.junit.Test

class ClusterStatsRotatorTest {

    @Test
    fun pagesIncludeRideMapsAndNa() {
        val pages = ClusterStatsRotator.pages(
            ClusterStatsRotator.StatsSnapshot(
                rideDurationMs = 90 * 60_000L,
                liveMileageKmL = 48,
                avgMileageKmL = 45.5,
                mapsEta = "N/A",
                mapsDistance = "N/A"
            )
        )
        assertThat(pages).hasSize(5)
        assertThat(pages[0].first).isEqualTo("Ride time:")
        assertThat(pages[0].second).isEqualTo("1h 30m")
        assertThat(pages[1]).isEqualTo("Live mileage:" to "48 km/L")
        assertThat(pages[2]).isEqualTo("Avg mileage:" to "45.5 km/L")
        assertThat(pages[3]).isEqualTo("Maps ETA:" to "N/A")
        assertThat(pages[4]).isEqualTo("Distance left:" to "N/A")
    }

    @Test
    fun sanitizeKeepsColonAndSlash() {
        assertThat(PacketBuilder.sanitizeClusterText("Maps ETA: 15m")).isEqualTo("Maps ETA: 15m")
        assertThat(PacketBuilder.sanitizeClusterText("48 km/L")).isEqualTo("48 km/L")
    }

    @Test
    fun mapsParserExtractsEtaAndDistance() {
        val snap = MapsNavParser.parse("Turn left onto Mount Road", "15 min · 4.2 km")
        assertThat(snap.etaText).isEqualTo("15m")
        assertThat(snap.remainingDistanceText).isEqualTo("4.2 km")
    }

    @Test
    fun mapsParserExtractsClockEta() {
        val snap = MapsNavParser.parse("ETA 4:32 PM", "8 km remaining")
        assertThat(snap.etaText).contains("4:32")
        assertThat(snap.remainingDistanceText).isEqualTo("8 km")
    }

    @Test
    fun mapsSnapshotShowsNaWhenStale() {
        val stale = MapsNavSnapshot(
            etaText = "10m",
            remainingDistanceText = "3 km",
            updatedAtMs = System.currentTimeMillis() - MapsNavSnapshot.STALE_MS - 1
        )
        assertThat(stale.etaOrNa()).isEqualTo("N/A")
        assertThat(stale.distanceOrNa()).isEqualTo("N/A")
    }

    @Test
    fun isMapsPackageDetectsGoogleMaps() {
        assertThat(MapsNavParser.isMapsPackage("com.google.android.apps.maps")).isTrue()
        assertThat(MapsNavParser.isMapsPackage("com.whatsapp")).isFalse()
    }
}
