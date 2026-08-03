package com.itvs.connect.ble

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.itvs.connect.data.PreferencesRepository

/**
 * Mirrors selected app notifications onto the scooter cluster (17-char rows).
 * Also harvests Google Maps ETA / remaining distance for the ride-stats rotator.
 * Full turn-by-turn HUD remains v2.
 */
class NotificationMirrorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PreferencesRepository

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        if (MapsNavParser.isMapsPackage(pkg)) {
            harvestMaps(sbn)
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
            MapsNavigationStore.clear()
        }
    }

    private fun harvestMaps(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val subText = extras.getCharSequence("android.subText")?.toString()
        val infoText = extras.getCharSequence("android.infoText")?.toString()
        val parsed = MapsNavParser.parse(title, text, bigText, subText, infoText)
        if (parsed.etaText != null || parsed.remainingDistanceText != null) {
            MapsNavigationStore.update(parsed.etaText, parsed.remainingDistanceText)
        }
    }
}
