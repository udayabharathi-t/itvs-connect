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
        assertThat(snap.timeToDestinationText).isEqualTo("15m")
        assertThat(snap.remainingDistanceText).isEqualTo("4.2 km")
    }

    @Test
    fun mapsParserParsesClassicNavTimeLine() {
        val snap = MapsNavParser.parse("15 min · 4.2 km · 4:32 PM")
        // Time-to-destination prefers duration; clock kept separately.
        assertThat(snap.timeToDestinationText).isEqualTo("15m")
        assertThat(snap.etaClockText).isEqualTo("4:32 PM")
        assertThat(snap.remainingDistanceText).isEqualTo("4.2 km")
        assertThat(snap.etaText).isEqualTo("15m")
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
        assertThat(snap.etaClockText).contains("5:10")
        assertThat(snap.timeToDestinationText).isEqualTo("28m")
        assertThat(snap.remainingDistanceText).isEqualTo("12.5 km")
    }

    @Test
    fun mapsParserStripsFieldPrefix() {
        val snap = MapsNavParser.parse("FIELD:nav_time=15 min · 4.2 km · 4:32 PM")
        assertThat(snap.timeToDestinationText).isEqualTo("15m")
        assertThat(snap.etaClockText).isEqualTo("4:32 PM")
        assertThat(snap.remainingDistanceText).isEqualTo("4.2 km")
    }

    @Test
    fun mapsSnapshotShowsNaWhenStale() {
        val stale = MapsNavSnapshot(
            timeToDestinationText = "10m",
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
        assertThat(snap.timeToDestinationText).isEqualTo("15m")
        assertThat(snap.etaClockText).isEqualTo("4:32PM")
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
        assertThat(ClusterStatsRotator.NAV_PAGE_LABELS).hasSize(ClusterStatsRotator.NAV_PAGE_COUNT)
    }

    @Test
    fun gmapsFieldsParseNextTurnDestAndTime() {
        val snap = MapsNavParser.fromGmapsFields(
            mapOf(
                "nav_title" to "200 m",
                "nav_description" to "Turn right onto Mount Road",
                "nav_time" to "15 min · 4.2 km · 4:32 PM"
            )
        )
        assertThat(snap.nextTurnDistanceText).isEqualTo("200 m")
        assertThat(snap.nextTurnDistanceMeters).isEqualTo(200)
        assertThat(snap.nextTurnInstruction).contains("Turn right")
        assertThat(snap.nextTurnManeuverOrNull()).isEqualTo("Turn right")
        assertThat(snap.remainingDistanceText).isEqualTo("4.2 km")
        assertThat(snap.timeToDestinationText).isEqualTo("15m")
        assertThat(snap.timeLeftOrNa()).isEqualTo("15m")
        assertThat(snap.etaClockText).isEqualTo("4:32 PM")
        // Time left must not fall back to arrival clock.
        assertThat(snap.timeLeftOrNa()).doesNotContain("4:32")
        assertThat(snap.isApproachLock).isTrue()
    }

    @Test
    fun destLeftPrefersTripDistanceOverNextTurn() {
        val snap = MapsNavParser.fromGmapsFields(
            mapOf(
                "nav_title" to "150 m",
                "nav_description" to "Turn left",
                // Missing / wrong remaining in one field should not pin Dest to next-turn.
                "text" to "150 m",
                "nav_time" to "1 hr 10 min · 28.4 km · 6:15 PM"
            )
        )
        assertThat(snap.nextTurnDistanceText).isEqualTo("150 m")
        assertThat(snap.remainingDistanceText).isEqualTo("28.4 km")
        assertThat(snap.timeToDestinationText).isEqualTo("1h 10m")
        assertThat(snap.timeLeftOrNa()).isEqualTo("1h 10m")
    }

    @Test
    fun preferDestinationDistanceIgnoresNextTurnScale() {
        assertThat(
            MapsNavParser.preferDestinationDistance(
                candidate = "200 m",
                previous = "12.5 km",
                nextTurn = "200 m"
            )
        ).isEqualTo("12.5 km")
        assertThat(
            MapsNavParser.preferDestinationDistance(
                candidate = "200 m",
                previous = null,
                nextTurn = "200 m",
                extras = listOf("200 m", "8 km")
            )
        ).isEqualTo("8 km")
    }

    @Test
    fun asTravelDurationRejectsArrivalClock() {
        assertThat(MapsNavParser.asTravelDuration("4:32 PM")).isNull()
        assertThat(MapsNavParser.asTravelDuration("15 min")).isEqualTo("15m")
        assertThat(MapsNavParser.asTravelDuration("1 hr 5 min")).isEqualTo("1h 5m")
    }

    @Test
    fun abbreviateManeuverForCluster() {
        assertThat(MapsNavParser.abbreviateManeuver("Turn left onto Mount Road"))
            .isEqualTo("Turn left")
        assertThat(MapsNavParser.abbreviateManeuver("Make a U-turn")).isEqualTo("U turn")
        assertThat(PacketBuilder.sanitizeClusterText("Turn left")).isEqualTo("Turn left")
    }

    @Test
    fun approachLockOnlyUnder200Meters() {
        val far = MapsNavSnapshot(
            nextTurnDistanceText = "0.5 km",
            nextTurnDistanceMeters = 500,
            remainingDistanceText = "4 km",
            timeToDestinationText = "12m",
            updatedAtMs = System.currentTimeMillis()
        )
        assertThat(far.isApproachLock).isFalse()
        val near = far.copy(
            nextTurnDistanceText = "150 m",
            nextTurnDistanceMeters = 150
        )
        assertThat(near.isApproachLock).isTrue()
    }

    @Test
    fun navPagesOrder() {
        val pages = ClusterStatsRotator.navPages(
            ClusterStatsRotator.StatsSnapshot(
                rideDurationMs = null,
                rideDistanceKm = null,
                liveMileageKmL = null,
                tripKmPerLitre = null,
                avgSpeedKmh = null,
                tripCostRupees = null,
                mapsEta = "15m",
                mapsDistance = "4.2 km",
                nextTurnDistance = "200 m",
                nextTurnManeuver = "Turn left",
                timeToDestination = "15m",
                navigating = true,
                approachLock = true
            )
        )
        assertThat(pages.map { it.first }).containsExactly(
            "Turn left",
            "Dest left:",
            "Time left:"
        ).inOrder()
        assertThat(pages[0].second).isEqualTo("200 m")
        assertThat(pages[1].second).isEqualTo("4.2 km")
        assertThat(pages[2].second).isEqualTo("15m")
    }

    @Test
    fun fromLiveUsesDurationNotClockForTimeLeft() {
        val maps = MapsNavSnapshot(
            nextTurnDistanceText = "300 m",
            nextTurnDistanceMeters = 300,
            nextTurnInstruction = "Turn left onto Anna Salai",
            remainingDistanceText = "9.1 km",
            timeToDestinationText = "22m",
            etaClockText = "5:10 PM",
            updatedAtMs = System.currentTimeMillis()
        )
        val snap = ClusterStatsRotator.fromLive(null, null, maps)
        assertThat(snap.timeToDestination).isEqualTo("22m")
        assertThat(snap.mapsDistance).isEqualTo("9.1 km")
        assertThat(snap.nextTurnManeuver).isEqualTo("Turn left")
        val pages = ClusterStatsRotator.navPages(snap)
        assertThat(pages[0].first).isEqualTo("Turn left")
        assertThat(pages[2].second).isEqualTo("22m")
    }

    @Test
    fun distanceToMetersHandlesKmAndMeters() {
        assertThat(MapsNavParser.distanceToMeters("200 m")).isEqualTo(200)
        assertThat(MapsNavParser.distanceToMeters("0.2 km")).isEqualTo(200)
        assertThat(MapsNavParser.distanceToMeters("1.5 km")).isEqualTo(1500)
    }

    @Test
    fun sanitizeKeepsNavLabels() {
        assertThat(PacketBuilder.sanitizeClusterText("Next turn:")).isEqualTo("Next turn:")
        assertThat(PacketBuilder.sanitizeClusterText("Dest left:")).isEqualTo("Dest left:")
        assertThat(PacketBuilder.sanitizeClusterText("Time left:")).isEqualTo("Time left:")
        assertThat(PacketBuilder.sanitizeClusterText("200 m")).isEqualTo("200 m")
    }
}
