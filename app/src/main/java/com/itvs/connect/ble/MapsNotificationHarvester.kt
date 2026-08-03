package com.itvs.connect.ble

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.view.children

/**
 * Harvests Google Maps navigation text.
 *
 * Maps often puts ETA / remaining distance in a custom RemoteViews layout where
 * standard extras (`android.title` / `android.text`) are null — so we inflate
 * the notification view and collect every TextView string, then regex-parse.
 */
object MapsNotificationHarvester {

    fun harvest(context: Context, sbn: StatusBarNotification): MapsNavSnapshot {
        val fromExtras = harvestExtras(sbn.notification)
        val fromViews = runCatching { harvestRemoteViews(context, sbn) }.getOrNull()
            ?: MapsNavSnapshot.Empty
        return merge(fromExtras, fromViews)
    }

    fun harvestActive(context: Context, notifications: Array<StatusBarNotification>?): MapsNavSnapshot {
        if (notifications == null) return MapsNavSnapshot.Empty
        var best = MapsNavSnapshot.Empty
        for (sbn in notifications) {
            if (!MapsNavParser.isMapsPackage(sbn.packageName)) continue
            val snap = harvest(context, sbn)
            if (snap.etaText != null || snap.remainingDistanceText != null) {
                best = merge(best, snap)
            }
        }
        return best
    }

    private fun harvestExtras(notification: Notification): MapsNavSnapshot {
        val extras = notification.extras
        val parts = mutableListOf<String?>()
        parts += extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        parts += extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        parts += extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        parts += extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        parts += extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        parts += extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        parts += extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        parts += notification.tickerText?.toString()
        // Some Maps builds stash strings under opaque keys — scan CharSequences.
        for (key in extras.keySet()) {
            val v = extras.get(key) ?: continue
            when (v) {
                is CharSequence -> parts += v.toString()
                is Array<*> -> v.filterIsInstance<CharSequence>().forEach { parts += it.toString() }
            }
        }
        return MapsNavParser.parse(*parts.toTypedArray())
    }

    private fun harvestRemoteViews(context: Context, sbn: StatusBarNotification): MapsNavSnapshot {
        val pkg = sbn.packageName ?: return MapsNavSnapshot.Empty
        val mapsCx = runCatching {
            context.createPackageContext(pkg, Context.CONTEXT_RESTRICTED)
        }.getOrNull() ?: return MapsNavSnapshot.Empty

        val remoteViews = contentView(context, sbn.notification) ?: return MapsNavSnapshot.Empty
        val inflater = mapsCx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as? LayoutInflater
            ?: return MapsNavSnapshot.Empty
        val root = runCatching {
            inflater.inflate(remoteViews.layoutId, null) as? ViewGroup
        }.getOrNull() ?: return MapsNavSnapshot.Empty

        runCatching { remoteViews.reapply(mapsCx, root) }
        val texts = mutableListOf<String>()
        collectText(root, mapsCx, texts)
        if (texts.isEmpty()) return MapsNavSnapshot.Empty
        return MapsNavParser.parse(*texts.toTypedArray())
    }

    private fun contentView(context: Context, notification: Notification): RemoteViews? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val builder = runCatching {
                Notification.Builder.recoverBuilder(context, notification)
            }.getOrNull()
            val big = runCatching { builder?.createBigContentView() }.getOrNull()
            if (big != null) return big
            val normal = runCatching { builder?.createContentView() }.getOrNull()
            if (normal != null) return normal
        }
        @Suppress("DEPRECATION")
        return notification.bigContentView ?: notification.contentView
    }

    private fun collectText(view: View, mapsCx: Context, out: MutableList<String>) {
        when (view) {
            is TextView -> {
                val t = view.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) out += t
                // Named fields from Maps layouts (when resource ids resolve).
                val name = runCatching {
                    if (view.id > 0) mapsCx.resources.getResourceEntryName(view.id) else null
                }.getOrNull()
                if (name != null && t.isNotEmpty()) {
                    // Prefer named fields by duplicating with a label hint for the parser.
                    when (name) {
                        "nav_time", "header_text", "lockscreen_eta", "text" ->
                            out += "ETA_HINT $t"
                        else -> Unit
                    }
                }
            }
            is ViewGroup -> view.children.forEach { collectText(it, mapsCx, out) }
        }
    }

    private fun merge(a: MapsNavSnapshot, b: MapsNavSnapshot): MapsNavSnapshot {
        if (a.etaText == null && a.remainingDistanceText == null) return b
        if (b.etaText == null && b.remainingDistanceText == null) return a
        return MapsNavSnapshot(
            etaText = b.etaText ?: a.etaText,
            remainingDistanceText = b.remainingDistanceText ?: a.remainingDistanceText,
            updatedAtMs = maxOf(a.updatedAtMs, b.updatedAtMs).coerceAtLeast(System.currentTimeMillis())
        )
    }
}
