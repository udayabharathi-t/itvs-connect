package com.itvs.connect.ble

import com.itvs.connect.data.RideTracker
import com.itvs.connect.util.Formatters
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Continuously feeds **one** ride/Maps stat onto the cluster message rows so the
 * scooter does not fall back to Assist ready / native economy.
 *
 * The Voice button advances to the next page ([nextPage]); it does **not**
 * auto-rotate on a timer.
 */
class ClusterStatsRotator(
    private val scope: CoroutineScope,
    private val refreshMs: Long = REFRESH_MS,
    private val flash: (row1: String, row2: String) -> Unit,
    private val provider: () -> StatsSnapshot
) {
    private var job: Job? = null
    private var index = 0

    val isRunning: Boolean get() = job?.isActive == true
    val currentIndex: Int get() = index

    /** Start continuous feed on the first page (or keep current index). */
    fun start(resetIndex: Boolean = true) {
        if (resetIndex) index = 0
        NotificationMirrorService.setFastMapsPoll(true)
        NotificationMirrorService.requestMapsPoll()
        if (job?.isActive == true) {
            // Already feeding — push current page immediately.
            pushCurrent()
            return
        }
        job = scope.launch {
            while (isActive) {
                NotificationMirrorService.requestMapsPoll()
                pushCurrent()
                delay(refreshMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        index = 0
        NotificationMirrorService.setFastMapsPoll(false)
    }

    /**
     * Advance to the next page. Starts continuous feed if it was off.
     * @return label of the page now shown
     */
    fun nextPage(): String {
        val pageCount = pages(provider()).size.coerceAtLeast(1)
        if (!isRunning) {
            index = 0
            start(resetIndex = false)
        } else {
            index = (index + 1) % pageCount
            pushCurrent()
        }
        return pages(provider()).getOrElse(index) { "Stats" to "N/A" }.first
    }

    /**
     * Jump to a specific page from the phone UI. Starts continuous feed if needed.
     * @return label of the page now shown
     */
    fun showPage(pageIndex: Int): String {
        val list = pages(provider())
        val pageCount = list.size.coerceAtLeast(1)
        index = pageIndex.mod(pageCount)
        if (!isRunning) {
            start(resetIndex = false)
        } else {
            pushCurrent()
        }
        return list.getOrElse(index) { "Stats" to "N/A" }.first
    }

    fun toggle(): Boolean {
        return if (isRunning) {
            stop()
            false
        } else {
            start(resetIndex = true)
            true
        }
    }

    private fun pushCurrent() {
        val list = pages(provider())
        if (list.isEmpty()) return
        if (index !in list.indices) index = 0
        val page = list[index]
        flash(page.first, page.second)
    }

    data class StatsSnapshot(
        val rideDurationMs: Long?,
        val rideDistanceKm: Double?,
        val liveMileageKmL: Int?,
        val tripKmPerLitre: Double?,
        val avgSpeedKmh: Double?,
        /** Approx trip cost in whole rupees, or null → N/A. */
        val tripCostRupees: Int?,
        val mapsEta: String,
        val mapsDistance: String
    )

    companion object {
        /** Keep rewriting the cluster so Assist ready / native economy do not return. */
        const val REFRESH_MS = 2_000L
        const val PAGE_COUNT = 8

        /** Short labels for the phone-side page picker (order matches [pages]). */
        val PAGE_LABELS = listOf(
            "Ride time",
            "Ride km",
            "Live km/L",
            "Trip km/L",
            "Trip Cost",
            "Avg speed",
            "Maps ETA",
            "Dist left"
        )

        /**
         * Approx fuel spend: litres ≈ distance ÷ trip km/L, then × Rs/L.
         * Whole rupees only. Null when price, distance, or trip economy is missing.
         */
        fun approxTripCostRupees(
            distanceKm: Double?,
            tripKmPerLitre: Double?,
            fuelCostPerLitre: Int
        ): Int? {
            if (fuelCostPerLitre <= 0) return null
            val dist = distanceKm?.takeIf { it > 0.0 } ?: return null
            val kmL = tripKmPerLitre?.takeIf { it > 0.0 } ?: return null
            return ((dist / kmL) * fuelCostPerLitre).roundToInt().coerceAtLeast(0)
        }

        fun pages(s: StatsSnapshot): List<Pair<String, String>> {
            val rideTime = s.rideDurationMs?.let { Formatters.durationHoursMinutes(it) } ?: "N/A"
            val rideKm = s.rideDistanceKm?.takeIf { it > 0.0 }?.let { "%.1f km".format(it) } ?: "N/A"
            val live = s.liveMileageKmL?.takeIf { it in 1..99 }?.let { "$it km/L" } ?: "N/A"
            val trip = s.tripKmPerLitre?.takeIf { it > 0 }?.let { "%.1f km/L".format(it) } ?: "N/A"
            val tripCost = s.tripCostRupees?.takeIf { it >= 0 }?.let { "Rs $it" } ?: "N/A"
            val avgSpeed = s.avgSpeedKmh?.takeIf { it > 0 }?.let { "%.0f km/h".format(it) } ?: "N/A"
            return listOf(
                "Ride time:" to rideTime,
                "Ride km:" to rideKm,
                "Live km/L:" to live,
                "Trip km/L:" to trip,
                "Trip Cost:" to tripCost,
                "Avg speed:" to avgSpeed,
                "Maps ETA:" to s.mapsEta,
                "Dist left:" to s.mapsDistance
            )
        }

        fun fromLive(
            ride: RideTracker.ActiveRideUi?,
            liveKmL: Int?,
            maps: MapsNavSnapshot,
            fuelCostPerLitre: Int = 0
        ): StatsSnapshot {
            val distanceKm = ride?.distanceKm
            val tripKmL = ride?.tripKmPerLitre
            return StatsSnapshot(
                rideDurationMs = ride?.durationMs,
                rideDistanceKm = distanceKm,
                // Fresh live only — no sticky fallback to an old constant reading.
                liveMileageKmL = liveKmL?.takeIf { it in 1..99 },
                // Trip = running average of live samples; null → N/A.
                tripKmPerLitre = tripKmL,
                avgSpeedKmh = ride?.avgSpeedKmh,
                tripCostRupees = approxTripCostRupees(distanceKm, tripKmL, fuelCostPerLitre),
                mapsEta = maps.etaOrNa(),
                mapsDistance = maps.distanceOrNa()
            )
        }
    }
}
