package com.itvs.connect.ble

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.itvs.connect.data.PreferencesRepository

/**
 * Mirrors selected app notifications onto the scooter cluster (17-char rows).
 * Also harvests Google Maps ETA / remaining distance for the ride-stats rotator
 * by reading extras **and** inflating Maps' custom RemoteViews.
 * Full turn-by-turn HUD remains v2.
 */
class NotificationMirrorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PreferencesRepository
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        pollMapsNow()
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(5_000)
                pollMapsNow()
            }
        }
    }

    override fun onListenerDisconnected() {
        pollJob?.cancel()
        pollJob = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        if (MapsNavParser.isMapsPackage(pkg)) {
            // RemoteViews inflate is safer on the main thread.
            mainHandler.post { applyMapsHarvest(sbn) }
            return
        }

        // Non-maps: only transient notifications (skip ongoing)
        if (sbn.isOngoing) return

        scope.launch {
            val settings = prefs.settings.first()
            if (!settings.appNotificationsEnabled) return@launch

            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = extras.getCharSequence("android.text")?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return@launch

            val chunks = PacketBuilder.sanitizeClusterText("$title $text")
                .chunked(17)
                .ifEmpty { return@launch }

            val intent = Intent(this@NotificationMirrorService, ScooterBleService::class.java)
                .setAction(ScooterBleService.ACTION_CLUSTER_MESSAGE)
                .putExtra(ScooterBleService.EXTRA_ROW1, chunks.getOrElse(0) { "" })
                .putExtra(ScooterBleService.EXTRA_ROW2, chunks.getOrElse(1) { "" })
            startForegroundService(intent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (MapsNavParser.isMapsPackage(pkg)) {
            // Don't clear immediately — Maps often replaces the notif. Re-poll.
            mainHandler.postDelayed({ pollMapsNow() }, 750)
        }
    }

    private fun pollMapsNow() {
        mainHandler.post {
            val active = runCatching { activeNotifications }.getOrNull()
            val snap = MapsNotificationHarvester.harvestActive(this, active)
            if (snap.etaText != null || snap.remainingDistanceText != null) {
                MapsNavigationStore.update(snap)
            } else if (active?.none { MapsNavParser.isMapsPackage(it.packageName) } == true) {
                MapsNavigationStore.clear()
            }
        }
    }

    private fun applyMapsHarvest(sbn: StatusBarNotification) {
        val snap = MapsNotificationHarvester.harvest(this, sbn)
        if (snap.etaText != null || snap.remainingDistanceText != null) {
            MapsNavigationStore.update(snap)
        }
    }
}
