package com.itvs.connect.ble

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.itvs.connect.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Mirrors selected app notifications onto the scooter cluster (17-char rows).
 * Harvests Google Maps turn-by-turn fields (GMapsParser-style RemoteViews inflate)
 * for the navigation / ride-stats HUD.
 */
class NotificationMirrorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PreferencesRepository
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
        instanceRef = WeakReference(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instanceRef = WeakReference(this)
        Log.i(TAG, "Notification listener connected")
        pollMapsNow()
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // Faster while ride-stats HUD is requesting Maps values.
                val interval = if (fastPollRequested) 2_000L else 5_000L
                delay(interval)
                pollMapsNow()
            }
        }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected")
        pollJob?.cancel()
        pollJob = null
        if (instanceRef?.get() === this) instanceRef = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        if (instanceRef?.get() === this) instanceRef = null
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
            if (snap.hasNavData) {
                MapsNavigationStore.update(snap)
            } else if (active?.none { MapsNavParser.isMapsPackage(it.packageName) } == true) {
                MapsNavigationStore.clear()
            }
        }
    }

    private fun applyMapsHarvest(sbn: StatusBarNotification) {
        // Skip non-ongoing Maps chrome; TBT nav is ongoing.
        if (!sbn.isOngoing && sbn.id != 1) return
        val snap = MapsNotificationHarvester.harvest(this, sbn)
        if (snap.hasNavData) {
            MapsNavigationStore.update(snap)
        }
    }

    companion object {
        private const val TAG = "NotifMirror"
        @Volatile
        private var instanceRef: WeakReference<NotificationMirrorService>? = null
        @Volatile
        private var fastPollRequested: Boolean = false

        /** Ask the listener to poll Maps immediately (no-op if not connected). */
        fun requestMapsPoll() {
            instanceRef?.get()?.pollMapsNow()
        }

        /** While ride-stats HUD is active, poll Maps every 2s instead of 5s. */
        fun setFastMapsPoll(enabled: Boolean) {
            fastPollRequested = enabled
            if (enabled) requestMapsPoll()
        }
    }
}
