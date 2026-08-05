package com.itvs.connect

import com.google.common.truth.Truth.assertThat
import com.itvs.connect.ble.ClusterStatsRotator
import com.itvs.connect.ble.MapsNavParser
import com.itvs.connect.ble.MapsNavSnapshot
import com.itvs.connect.ble.PacketBuilder
import org.junit.Test

class ClusterStatsRotatorTest {

    @Test
    fun pagesIncludeRideKmLiveAndAvgSpeed() {
        val pages = ClusterStatsRotator.pages(
            ClusterStatsRotator.StatsSnapshot(
                rideDurationMs = 90 * 60_000L,
                rideDistanceKm = 42.5,
                liveMileageKmL = 48,
                tripKmPerLitre = 36.2,
                avgSpeedKmh = 28.0,
                tripCostRupees = 118,
                mapsEta = "N/A",
                mapsDistance = "N/A"
            )
        )
        assertThat(pages).hasSize(8)
        assertThat(pages.map { it.first }).containsExactly(
            "Ride time:",
            "Ride km:",
            "Live km/L:",
            "Trip km/L:",
            "Trip Cost:",
            "Avg speed:",
            "Maps ETA:",
            "Dist left:"
        ).inOrder()
        assertThat(pages[0].second).isEqualTo("1h 30m")
        assertThat(pages[1].second).isEqualTo("42.5 km")
        assertThat(pages[2].second).isEqualTo("48 km/L")
        assertThat(pages[3].second).isEqualTo("36.2 km/L")
        assertThat(pages[4].second).isEqualTo("Rs 118")
        assertThat(pages[5].second).isEqualTo("28 km/h")
    }

    @Test
    fun tripKmLShowsNaWhenMissing() {
        val pages = ClusterStatsRotator.pages(
            ClusterStatsRotator.StatsSnapshot(
                rideDurationMs = 60_000L,
                rideDistanceKm = 1.0,
                liveMileageKmL = 40,
                tripKmPerLitre = null,
                avgSpeedKmh = 10.0,
                tripCostRupees = null,
                mapsEta = "N/A",
                mapsDistance = "N/A"
            )
        )
        assertThat(pages.first { it.first == "Trip km/L:" }.second).isEqualTo("N/A")
        assertThat(pages.first { it.first == "Trip Cost:" }.second).isEqualTo("N/A")
        assertThat(pages.first { it.first == "Live km/L:" }.second).isEqualTo("40 km/L")
    }

    @Test
    fun fromLiveUsesFreshLiveAndTripAverageOnly() {
        val snap = ClusterStatsRotator.fromLive(
            ride = null,
            liveKmL = null,
            maps = MapsNavSnapshot.Empty,
            fuelCostPerLitre = 100
        )
        assertThat(snap.liveMileageKmL).isNull()
        assertThat(snap.tripKmPerLitre).isNull()
        assertThat(snap.tripCostRupees).isNull()
        val pages = ClusterStatsRotator.pages(snap)
        assertThat(pages.first { it.first == "Live km/L:" }.second).isEqualTo("N/A")
        assertThat(pages.first { it.first == "Trip km/L:" }.second).isEqualTo("N/A")
        assertThat(pages.first { it.first == "Trip Cost:" }.second).isEqualTo("N/A")
    }

    @Test
    fun approxTripCostUsesDistanceAndTripKmL() {
        // 40 km ÷ 40 km/L × Rs 100/L = Rs 100
        assertThat(
            ClusterStatsRotator.approxTripCostRupees(40.0, 40.0, 100)
        ).isEqualTo(100)
        // 42.5 ÷ 36.2 × 100 ≈ 117.4 → 117
        assertThat(
            ClusterStatsRotator.approxTripCostRupees(42.5, 36.2, 100)
        ).isEqualTo(117)
        assertThat(ClusterStatsRotator.approxTripCostRupees(10.0, 40.0, 0)).isNull()
        assertThat(ClusterStatsRotator.approxTripCostRupees(0.0, 40.0, 100)).isNull()
        assertThat(ClusterStatsRotator.approxTripCostRupees(10.0, null, 100)).isNull()
    }

    @Test
    fun sanitizeKeepsColonAndSlash() {
        assertThat(PacketBuilder.sanitizeClusterText("Maps ETA: 15m")).isEqualTo("Maps ETA: 15m")
        assertThat(PacketBuilder.sanitizeClusterText("48 km/L")).isEqualTo("48 km/L")
        assertThat(PacketBuilder.sanitizeClusterText("Trip Cost:")).isEqualTo("Trip Cost:")
        assertThat(PacketBuilder.sanitizeClusterText("Rs 245")).isEqualTo("Rs 245")
    }

    @Test
    fun mapsParserExtractsEtaAndDistance() {
        val snap = MapsNavParser.parse("Turn left onto Mount Road", "15 min · 4.2 km")
        assertThat(snap.etaText).isEqualTo("15m")
        assertThat(snap.remainingDistanceText).isEqualTo("4.2 km")
    }

    @Test
    fun mapsParserParsesClassicNavTimeLine() {
        val snap = MapsNavParser.parse("15 min · 4.2 km · 4:32 PM")
        assertThat(snap.etaText).isEqualTo("4:32 PM")
        assertThat(snap.remainingDistanceText).isEqualTo("4.2 km")
    }

    @Test
    fun mapsParserExtractsClockEta() {
        val snap = MapsNavParser.parse("ETA 4:32 PM", "8 km remaining")
        assertThat(snap.etaText).contains("4:32")
        assertThat(snap.remainingDistanceText).isEqualTo("8 km")
    }

    @Test
    fun mapsParserHandlesBulletAndArriveBy() {
        val snap = MapsNavParser.parse("Arrive by 5:10 PM • 12.5 km • 28 min")
        assertThat(snap.etaText).contains("5:10")
        assertThat(snap.remainingDistanceText).isEqualTo("12.5 km")
    }

    @Test
    fun mapsParserStripsFieldPrefix() {
        val snap = MapsNavParser.parse("FIELD:nav_time=15 min · 4.2 km · 4:32 PM")
        assertThat(snap.etaText).isEqualTo("4:32 PM")
        assertThat(snap.remainingDistanceText).isEqualTo("4.2 km")
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

    @Test
    fun mapsParserHandlesCompactGluedTokens() {
        val snap = MapsNavParser.parse("15min·4.2km·4:32PM")
        assertThat(snap.etaText).isEqualTo("4:32PM")
        assertThat(snap.remainingDistanceText).isEqualTo("4.2 km")
    }

    @Test
    fun mapsParserHandlesLeftDistance() {
        val snap = MapsNavParser.parse("left 3.5 km", "ETA 6:15 PM")
        assertThat(snap.etaText).contains("6:15")
        assertThat(snap.remainingDistanceText).isEqualTo("3.5 km")
    }

    @Test
    fun pageLabelsMatchPageCount() {
        assertThat(ClusterStatsRotator.PAGE_LABELS).hasSize(ClusterStatsRotator.PAGE_COUNT)
    }
}
