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
 * Navigation HUD is intentionally out of scope for v1.
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
        if (sbn == null || sbn.isOngoing) return
        val pkg = sbn.packageName ?: return
        // Skip own + maps (nav is v2)
        if (pkg == packageName) return
        if (pkg.contains("maps", ignoreCase = true)) return

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

            // Send first page immediately; service will paginate lightly
            val intent = Intent(this@NotificationMirrorService, ScooterBleService::class.java)
                .setAction(ScooterBleService.ACTION_CLUSTER_MESSAGE)
                .putExtra(ScooterBleService.EXTRA_ROW1, chunks.getOrElse(0) { "" })
                .putExtra(ScooterBleService.EXTRA_ROW2, chunks.getOrElse(1) { "" })
            startForegroundService(intent)
        }
    }
}
