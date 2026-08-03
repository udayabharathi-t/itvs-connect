package com.itvs.connect.ble

import com.itvs.connect.data.RideTracker
import com.itvs.connect.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Cycles ride / Maps stats onto the cluster message rows every [intervalMs].
 * Toggle on/off via the mapped Voice-button action.
 */
class ClusterStatsRotator(
    private val scope: CoroutineScope,
    private val intervalMs: Long = INTERVAL_MS,
    private val flash: (row1: String, row2: String) -> Unit,
    private val provider: () -> StatsSnapshot
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
            while (isActive) {
                val page = pages(provider()).getOrElse(index % PAGE_COUNT) {
                    "Stats" to "N/A"
                }
                flash(page.first, page.second)
                index = (index + 1) % PAGE_COUNT
                delay(intervalMs)
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
        val liveMileageKmL: Int?,
        val avgMileageKmL: Double?,
        val mapsEta: String,
        val mapsDistance: String
    )

    companion object {
        const val INTERVAL_MS = 10_000L
        const val PAGE_COUNT = 5

        fun pages(s: StatsSnapshot): List<Pair<String, String>> {
            val rideTime = s.rideDurationMs?.let { Formatters.durationHoursMinutes(it) } ?: "N/A"
            val live = s.liveMileageKmL?.takeIf { it in 1..99 }?.let { "$it km/L" } ?: "N/A"
            val avg = s.avgMileageKmL?.takeIf { it > 0 }?.let { "%.1f km/L".format(it) } ?: "N/A"
            return listOf(
                "Ride time:" to rideTime,
                "Live mileage:" to live,
                "Avg mileage:" to avg,
                "Maps ETA:" to s.mapsEta,
                "Distance left:" to s.mapsDistance
            )
        }

        fun fromLive(
            ride: RideTracker.ActiveRideUi?,
            liveAfe: Int,
            maps: MapsNavSnapshot
        ): StatsSnapshot {
            val avg = ride?.avgAfe
                ?: ride?.afe?.takeIf { it in 1..99 }?.toDouble()
            return StatsSnapshot(
                rideDurationMs = ride?.durationMs,
                liveMileageKmL = liveAfe.takeIf { it in 1..99 } ?: ride?.afe,
                avgMileageKmL = avg,
                mapsEta = maps.etaOrNa(),
                mapsDistance = maps.distanceOrNa()
            )
        }
    }
}
