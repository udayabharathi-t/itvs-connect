package com.itvs.connect.ble

import com.itvs.connect.data.RideTracker
import com.itvs.connect.util.Formatters
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
        if (job?.isActive == true) {
            // Already feeding — push current page immediately.
            pushCurrent()
            return
        }
        job = scope.launch {
            while (isActive) {
                pushCurrent()
                delay(refreshMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        index = 0
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
        val mapsEta: String,
        val mapsDistance: String
    )

    companion object {
        /** Keep rewriting the cluster so Assist ready / native economy do not return. */
        const val REFRESH_MS = 2_000L
        const val PAGE_COUNT = 7

        fun pages(s: StatsSnapshot): List<Pair<String, String>> {
            val rideTime = s.rideDurationMs?.let { Formatters.durationHoursMinutes(it) } ?: "N/A"
            val rideKm = s.rideDistanceKm?.takeIf { it > 0.0 }?.let { "%.1f km".format(it) } ?: "N/A"
            val live = s.liveMileageKmL?.takeIf { it in 1..99 }?.let { "$it km/L" } ?: "N/A"
            val trip = s.tripKmPerLitre?.takeIf { it > 0 }?.let { "%.1f km/L".format(it) } ?: "N/A"
            val avgSpeed = s.avgSpeedKmh?.takeIf { it > 0 }?.let { "%.0f km/h".format(it) } ?: "N/A"
            return listOf(
                "Ride time:" to rideTime,
                "Ride km:" to rideKm,
                "Live km/L:" to live,
                "Trip km/L:" to trip,
                "Avg speed:" to avgSpeed,
                "Maps ETA:" to s.mapsEta,
                "Dist left:" to s.mapsDistance
            )
        }

        fun fromLive(
            ride: RideTracker.ActiveRideUi?,
            liveAfe: Int,
            maps: MapsNavSnapshot
        ): StatsSnapshot {
            return StatsSnapshot(
                rideDurationMs = ride?.durationMs,
                rideDistanceKm = ride?.distanceKm,
                liveMileageKmL = liveAfe.takeIf { it in 1..99 } ?: ride?.afe,
                // Prefer fuel-bar trip economy; do not fall back to sticky cluster AFE.
                tripKmPerLitre = ride?.tripKmPerLitre,
                avgSpeedKmh = ride?.avgSpeedKmh,
                mapsEta = maps.etaOrNa(),
                mapsDistance = maps.distanceOrNa()
            )
        }
    }
}
