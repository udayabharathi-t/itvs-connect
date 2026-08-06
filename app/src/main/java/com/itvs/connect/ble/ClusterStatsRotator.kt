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
 * Continuously feeds **one** stat onto the cluster message rows so the scooter
 * does not fall back to Assist ready / native economy.
 *
 * Ride stats: Voice / phone picker advances pages (no timer rotate).
 * Navigation (Google Maps TBT active): auto-rotates Next turn → Dest left →
 * Time left; within 200 m of the next turn, locks on Next turn only.
 */
class ClusterStatsRotator(
    private val scope: CoroutineScope,
    private val refreshMs: Long = REFRESH_MS,
    private val flash: (row1: String, row2: String) -> Unit,
    private val provider: () -> StatsSnapshot
) {
    private var job: Job? = null
    private var index = 0
    private var navIndex = 0
    private var lastNavAdvanceAtMs = 0L
    private var wasNavigating = false

    val isRunning: Boolean get() = job?.isActive == true
    val currentIndex: Int get() = index
    val currentNavIndex: Int get() = navIndex

    /** Start continuous feed on the first page (or keep current index). */
    fun start(resetIndex: Boolean = true) {
        if (resetIndex) {
            index = 0
            navIndex = 0
            lastNavAdvanceAtMs = 0L
        }
        NotificationMirrorService.setFastMapsPoll(true)
        NotificationMirrorService.requestMapsPoll()
        if (job?.isActive == true) {
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
        navIndex = 0
        lastNavAdvanceAtMs = 0L
        wasNavigating = false
        NotificationMirrorService.setFastMapsPoll(false)
    }

    /**
     * Advance to the next page. Starts continuous feed if it was off.
     * During active navigation, advances the nav page (ignored under approach lock).
     */
    fun nextPage(): String {
        val snap = provider()
        if (snap.navigating) {
            if (!isRunning) start(resetIndex = false)
            if (!snap.approachLock) {
                navIndex = (navIndex + 1) % NAV_PAGE_COUNT
                lastNavAdvanceAtMs = System.currentTimeMillis()
            }
            pushCurrent()
            return navPages(snap).getOrElse(navIndex) { "Nav" to "N/A" }.first
        }
        val pageCount = pages(snap).size.coerceAtLeast(1)
        if (!isRunning) {
            index = 0
            start(resetIndex = false)
        } else {
            index = (index + 1) % pageCount
            pushCurrent()
        }
        return pages(snap).getOrElse(index) { "Stats" to "N/A" }.first
    }

    /**
     * Jump to a specific page from the phone UI. Starts continuous feed if needed.
     * While navigating, [pageIndex] selects among the 3 nav pages.
     */
    fun showPage(pageIndex: Int): String {
        val snap = provider()
        if (snap.navigating) {
            navIndex = pageIndex.mod(NAV_PAGE_COUNT)
            lastNavAdvanceAtMs = System.currentTimeMillis()
            if (!isRunning) start(resetIndex = false) else pushCurrent()
            return navPages(snap).getOrElse(navIndex) { "Nav" to "N/A" }.first
        }
        val list = pages(snap)
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
        val snap = provider()
        if (snap.navigating) {
            if (!wasNavigating) {
                navIndex = 0
                lastNavAdvanceAtMs = System.currentTimeMillis()
                wasNavigating = true
            }
            val page = if (snap.approachLock) {
                navPages(snap).first()
            } else {
                val now = System.currentTimeMillis()
                if (lastNavAdvanceAtMs == 0L) lastNavAdvanceAtMs = now
                if (now - lastNavAdvanceAtMs >= NAV_ROTATE_MS) {
                    navIndex = (navIndex + 1) % NAV_PAGE_COUNT
                    lastNavAdvanceAtMs = now
                }
                navPages(snap).getOrElse(navIndex) { navPages(snap).first() }
            }
            flash(page.first, page.second)
            return
        }
        wasNavigating = false
        val list = pages(snap)
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
        val mapsDistance: String,
        val nextTurnDistance: String = "N/A",
        val timeToDestination: String = "N/A",
        val navigating: Boolean = false,
        val approachLock: Boolean = false
    )

    companion object {
        /** Keep rewriting the cluster so Assist ready / native economy do not return. */
        const val REFRESH_MS = 2_000L
        /** Auto-rotate interval between nav pages while navigating (not approach-locked). */
        const val NAV_ROTATE_MS = 4_000L
        const val PAGE_COUNT = 8
        const val NAV_PAGE_COUNT = 3

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

        /** Labels while Google Maps navigation is active. */
        val NAV_PAGE_LABELS = listOf(
            "Next turn",
            "Dest left",
            "Time left"
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

        fun navPages(s: StatsSnapshot): List<Pair<String, String>> {
            return listOf(
                "Next turn:" to s.nextTurnDistance,
                "Dest left:" to s.mapsDistance,
                "Time left:" to s.timeToDestination
            )
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
                liveMileageKmL = liveKmL?.takeIf { it in 1..99 },
                tripKmPerLitre = tripKmL,
                avgSpeedKmh = ride?.avgSpeedKmh,
                tripCostRupees = approxTripCostRupees(distanceKm, tripKmL, fuelCostPerLitre),
                mapsEta = maps.etaOrNa(),
                mapsDistance = maps.distanceOrNa(),
                nextTurnDistance = maps.nextTurnOrNa(),
                timeToDestination = maps.etaOrNa(),
                navigating = maps.isNavigating,
                approachLock = maps.isApproachLock
            )
        }
    }
}
