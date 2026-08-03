package com.itvs.connect.ble

import com.itvs.connect.data.RideTracker
import com.itvs.connect.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Cycles ride / Maps stats onto the cluster message rows.
 *
 * Advances page every [intervalMs]. Re-sends the current page every [refreshMs]
 * so the cluster does not fall back to Assist ready / native economy between ticks.
 */
class ClusterStatsRotator(
    private val scope: CoroutineScope,
    private val intervalMs: Long = INTERVAL_MS,
    private val refreshMs: Long = REFRESH_MS,
    private val flash: (row1: String, row2: String) -> Unit,
    private val provider: () -> StatsSnapshot,
    private val onTick: (() -> Unit)? = null
) {
    private var job: Job? = null
    private var index = 0

    val isRunning: Boolean get() = job?.isActive == true

    fun toggle(): Boolean {
        return if (isRunning) {
            stop()
            false
        } else {
            start()
            true
        }
    }

    fun start() {
        stop()
        index = 0
        job = scope.launch {
            var elapsedOnPage = 0L
            while (isActive) {
                onTick?.invoke()
                val pages = pages(provider())
                val page = pages.getOrElse(index % pages.size) { "Stats" to "N/A" }
                flash(page.first, page.second)
                delay(refreshMs)
                elapsedOnPage += refreshMs
                if (elapsedOnPage >= intervalMs) {
                    elapsedOnPage = 0L
                    index = (index + 1) % pages.size.coerceAtLeast(1)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        index = 0
    }

    data class StatsSnapshot(
        val rideDurationMs: Long?,
        val avgMileageKmL: Double?,
        val mapsEta: String,
        val mapsDistance: String
    )

    companion object {
        const val INTERVAL_MS = 10_000L
        /** Keep rewriting the cluster so Assist ready / native economy do not return. */
        const val REFRESH_MS = 2_500L
        const val PAGE_COUNT = 4

        fun pages(s: StatsSnapshot): List<Pair<String, String>> {
            val rideTime = s.rideDurationMs?.let { Formatters.durationHoursMinutes(it) } ?: "N/A"
            val avg = s.avgMileageKmL?.takeIf { it > 0 }?.let { "%.1f km/L".format(it) } ?: "N/A"
            return listOf(
                "Ride time:" to rideTime,
                "Avg mileage:" to avg,
                "Maps ETA:" to s.mapsEta,
                "Distance left:" to s.mapsDistance
            )
        }

        fun fromLive(
            ride: RideTracker.ActiveRideUi?,
            maps: MapsNavSnapshot
        ): StatsSnapshot {
            val avg = ride?.avgAfe
                ?: ride?.afe?.takeIf { it in 1..99 }?.toDouble()
            return StatsSnapshot(
                rideDurationMs = ride?.durationMs,
                avgMileageKmL = avg,
                mapsEta = maps.etaOrNa(),
                mapsDistance = maps.distanceOrNa()
            )
        }
    }
}
