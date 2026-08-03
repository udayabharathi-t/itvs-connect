package com.itvs.connect

import com.google.common.truth.Truth.assertThat
import com.itvs.connect.data.PlaceNameResolver
import com.itvs.connect.data.PlaceParts
import com.itvs.connect.data.RideEntity
import org.junit.Test

class PlaceNameResolverTest {

    @Test
    fun displayTitlePrefersCustomLabel() {
        val ride = sampleRide(
            label = "Office commute",
            startPlaceName = "Anna Nagar",
            endPlaceName = "T Nagar"
        )
        assertThat(PlaceNameResolver.displayTitle(ride)).isEqualTo("Office commute")
    }

    @Test
    fun displayTitleUsesStartToEndWhenNoLabel() {
        val ride = sampleRide(
            label = "",
            startPlaceName = "Anna Nagar",
            endPlaceName = "T Nagar"
        )
        assertThat(PlaceNameResolver.displayTitle(ride)).isEqualTo("Anna Nagar → T Nagar")
    }

    @Test
    fun displayTitleCollapsesIdenticalPlaces() {
        val ride = sampleRide(
            label = "",
            startPlaceName = "Home",
            endPlaceName = "Home"
        )
        assertThat(PlaceNameResolver.displayTitle(ride)).isEqualTo("Home")
    }

    @Test
    fun formatPartsPrefersStreetAndLocality() {
        val label = PlaceNameResolver.formatParts(
            PlaceParts(
                thoroughfare = "Mount Road",
                locality = "Chennai",
                adminArea = "Tamil Nadu",
                featureName = "Mount Road",
                addressLine = "Mount Road, Chennai, Tamil Nadu"
            )
        )
        assertThat(label).isEqualTo("Mount Road, Chennai, Tamil Nadu")
    }

    @Test
    fun shortenTruncatesLongNames() {
        val long = "A".repeat(80)
        val short = PlaceNameResolver.shorten(long, maxLen = 40)
        assertThat(short.length).isAtMost(40)
        assertThat(short).endsWith("…")
    }

    private fun sampleRide(
        label: String,
        startPlaceName: String?,
        endPlaceName: String?
    ) = RideEntity(
        id = 1,
        startTimeMs = 1_000,
        endTimeMs = 2_000,
        durationMs = 1_000,
        distanceKm = 1.0,
        startOdometerKm = 1.0,
        endOdometerKm = 2.0,
        startFuelPercent = 50,
        endFuelPercent = 40,
        clusterAfeKmL = 40,
        approxKmPerLitre = 40.0,
        estimatedLitresUsed = 0.2,
        economySource = "FUEL_DELTA",
        avgSpeedKmh = 30.0,
        maxSpeedKmh = 40.0,
        startLat = 13.0,
        startLng = 80.0,
        endLat = 13.01,
        endLng = 80.01,
        routeJson = "[]",
        label = label,
        startPlaceName = startPlaceName,
        endPlaceName = endPlaceName
    )
}
