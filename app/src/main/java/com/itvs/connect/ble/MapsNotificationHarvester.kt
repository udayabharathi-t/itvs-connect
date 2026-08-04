package com.itvs.connect.ble

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.view.children
import java.lang.reflect.Array as ReflectArray
import java.util.concurrent.atomic.AtomicReference

/**
 * Harvests Google Maps navigation text.
 *
 * Maps often uses custom RemoteViews with empty title/text extras. We try:
 * 1) all CharSequence extras (including nested bundles)
 * 2) reflection over RemoteViews mActions (setText payloads + nested RVs)
 * 3) RemoteViews.apply() / reapply() + walk TextViews
 */
object MapsNotificationHarvester {

    private const val TAG = "MapsHarvest"

    data class HarvestDebug(
        val listenerEnabled: Boolean = false,
        val mapsNotifSeen: Boolean = false,
        val lastPackage: String? = null,
        val lastRawPreview: String = "",
        val lastEta: String? = null,
        val lastDistance: String? = null,
        val lastError: String? = null,
        val updatedAtMs: Long = 0L
    )

    private val debugRef = AtomicReference(HarvestDebug())
    val debug: HarvestDebug get() = debugRef.get()

    fun refreshListenerFlag(context: Context) {
        val ok = isNotificationAccessEnabled(context)
        val prev = debugRef.get()
        if (prev.listenerEnabled != ok) {
            debugRef.set(prev.copy(listenerEnabled = ok, updatedAtMs = System.currentTimeMillis()))
        }
    }

    fun isNotificationAccessEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val cn = ComponentName(context, NotificationMirrorService::class.java)
        val want = cn.flattenToString()
        return flat.split(':', ';', ',').any { entry ->
            val e = entry.trim()
            e.equals(want, ignoreCase = true) ||
                (e.contains(context.packageName, ignoreCase = true) &&
                    e.contains("NotificationMirrorService", ignoreCase = true))
        }
    }

    fun harvest(context: Context, sbn: StatusBarNotification): MapsNavSnapshot {
        val listenerOk = isNotificationAccessEnabled(context)
        val texts = mutableListOf<String>()
        var error: String? = null

        runCatching { texts += harvestExtras(sbn.notification) }
            .onFailure { error = "extras: ${it.message}" }

        val remoteViewsList = allRemoteViews(context, sbn.notification)
        for (rv in remoteViewsList) {
            runCatching { texts += extractActionsText(rv) }
                .onFailure { error = listOfNotNull(error, "actions: ${it.message}").joinToString("; ") }
            runCatching { texts += harvestAppliedViews(context, sbn.packageName, rv) }
                .onFailure { error = listOfNotNull(error, "apply: ${it.message}").joinToString("; ") }
        }

        val unique = texts
            .asSequence()
            .map { normalizeHarvested(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val parsed = MapsNavParser.parse(*unique.toTypedArray())
        val preview = unique.joinToString(" | ").take(200)
        val snap = parsed.copy(rawPreview = preview.ifBlank { parsed.rawPreview })
        debugRef.set(
            HarvestDebug(
                listenerEnabled = listenerOk,
                mapsNotifSeen = true,
                lastPackage = sbn.packageName,
                lastRawPreview = preview.ifBlank { "(no text extracted)" },
                lastEta = snap.etaText,
                lastDistance = snap.remainingDistanceText,
                lastError = if (snap.etaText == null && snap.remainingDistanceText == null) {
                    error ?: if (unique.isEmpty()) {
                        "Maps notif seen but no readable text"
                    } else {
                        "Could not parse ETA/distance from: ${preview.take(80)}"
                    }
                } else null,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        Log.i(
            TAG,
            "pkg=${sbn.packageName} texts=${unique.size} eta=${snap.etaText} " +
                "dist=${snap.remainingDistanceText} preview=$preview"
        )
        return snap
    }

    fun harvestActive(context: Context, notifications: Array<StatusBarNotification>?): MapsNavSnapshot {
        val listenerOk = isNotificationAccessEnabled(context)
        if (notifications == null) {
            debugRef.set(
                debug.copy(
                    listenerEnabled = listenerOk,
                    lastError = if (!listenerOk) {
                        "Enable Notification access for iTVS Connect"
                    } else {
                        "activeNotifications=null (listener not connected?)"
                    },
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            return MapsNavSnapshot.Empty
        }

        var best = MapsNavSnapshot.Empty
        var sawMaps = false
        for (sbn in notifications) {
            if (!MapsNavParser.isMapsPackage(sbn.packageName)) continue
            sawMaps = true
            val snap = harvest(context, sbn)
            if (snap.etaText != null || snap.remainingDistanceText != null) {
                best = merge(best, snap)
            }
        }
        if (!sawMaps) {
            debugRef.set(
                HarvestDebug(
                    listenerEnabled = listenerOk,
                    mapsNotifSeen = false,
                    lastRawPreview = "",
                    lastError = if (!listenerOk) {
                        "Enable Notification access (More → Notification access)"
                    } else {
                        "No Google Maps navigation notification — start turn-by-turn nav"
                    },
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
        return best
    }

    private fun normalizeHarvested(raw: String): String {
        var s = raw.replace('\u00A0', ' ').trim()
        // Drop FIELD:name= prefix from inflated view id tagging
        if (s.startsWith("FIELD:")) {
            s = s.substringAfter('=', missingDelimiterValue = s.removePrefix("FIELD:"))
        }
        return s.trim()
    }

    private fun harvestExtras(notification: Notification): List<String> {
        val extras = notification.extras ?: return emptyList()
        val parts = mutableListOf<String>()
        fun add(cs: CharSequence?) {
            val s = cs?.toString()?.trim().orEmpty()
            if (s.isNotBlank() && s.length < 400) parts += s
        }
        add(extras.getCharSequence(Notification.EXTRA_TITLE))
        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
            extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.forEach { add(it) }
        }
        add(notification.tickerText)
        collectFromBundle(extras, parts)
        return parts
    }

    private fun collectFromBundle(bundle: Bundle, parts: MutableList<String>, depth: Int = 0) {
        if (depth > 4) return
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            when (val v = bundle.get(key)) {
                is CharSequence -> {
                    val s = v.toString().trim()
                    if (s.isNotBlank() && s.length < 400) parts += s
                }
                is Array<*> -> v.filterIsInstance<CharSequence>().forEach {
                    val s = it.toString().trim()
                    if (s.isNotBlank() && s.length < 400) parts += s
                }
                is ArrayList<*> -> v.filterIsInstance<CharSequence>().forEach {
                    val s = it.toString().trim()
                    if (s.isNotBlank() && s.length < 400) parts += s
                }
                is Bundle -> collectFromBundle(v, parts, depth + 1)
            }
        }
    }

    private fun allRemoteViews(context: Context, notification: Notification): List<RemoteViews> {
        val list = mutableListOf<RemoteViews>()
        fun add(rv: RemoteViews?) {
            if (rv != null) list += rv
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val builder = runCatching {
                Notification.Builder.recoverBuilder(context, notification)
            }.getOrNull()
            add(runCatching { builder?.createBigContentView() }.getOrNull())
            add(runCatching { builder?.createContentView() }.getOrNull())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(runCatching { builder?.createHeadsUpContentView() }.getOrNull())
            }
        }
        @Suppress("DEPRECATION")
        add(notification.bigContentView)
        @Suppress("DEPRECATION")
        add(notification.contentView)
        @Suppress("DEPRECATION")
        add(notification.headsUpContentView)

        // Public API on newer platforms for custom content
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                val m = Notification::class.java.getMethod("getContentView")
                add(m.invoke(notification) as? RemoteViews)
            }
        }

        return list.distinctBy { System.identityHashCode(it) }
    }

    /**
     * Pull CharSequences out of RemoteViews action list (setText / setCharSequence)
     * and recurse into nested RemoteViews. Works even when layout inflate fails.
     */
    private fun extractActionsText(remoteViews: RemoteViews): List<String> {
        val out = mutableListOf<String>()
        walkRemoteViewsActions(remoteViews, out, depth = 0)
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun walkRemoteViewsActions(remoteViews: RemoteViews, out: MutableList<String>, depth: Int) {
        if (depth > 6) return
        val field = runCatching {
            RemoteViews::class.java.getDeclaredField("mActions").also { it.isAccessible = true }
        }.getOrNull() ?: return
        val actions = runCatching { field.get(remoteViews) }.getOrNull() ?: return
        val list: List<Any?> = when (actions) {
            is ArrayList<*> -> actions
            is List<*> -> actions
            else -> {
                if (actions.javaClass.isArray) {
                    (0 until ReflectArray.getLength(actions)).map { ReflectArray.get(actions, it) }
                } else emptyList()
            }
        }
        for (action in list) {
            if (action == null) continue
            collectActionStrings(action, out)
            // Nested RemoteViews (landscape/portrait / ViewGroupAction)
            findNestedRemoteViews(action).forEach { walkRemoteViewsActions(it, out, depth + 1) }
        }
    }

    private fun collectActionStrings(action: Any, out: MutableList<String>) {
        val methodName = readField(action, "methodName") as? String
        val interesting = methodName == null ||
            methodName.contains("text", ignoreCase = true) ||
            methodName.contains("Text", ignoreCase = false) ||
            methodName.equals("setContentDescription", ignoreCase = true)

        val candidates = mutableListOf<Any?>()
        candidates += readField(action, "value")
        candidates += readField(action, "charSequence")
        candidates += readField(action, "text")
        candidates += readField(action, "string")
        // Sweep declared fields one level for CharSequences (OEM variants)
        var clazz: Class<*>? = action.javaClass
        var sweeps = 0
        while (clazz != null && clazz != Any::class.java && sweeps < 4) {
            for (f in clazz.declaredFields) {
                runCatching {
                    f.isAccessible = true
                    candidates += f.get(action)
                }
            }
            clazz = clazz.superclass
            sweeps++
        }

        if (!interesting && methodName != null) {
            // Still accept obvious duration/distance-looking strings from any action
            for (c in candidates) {
                val s = (c as? CharSequence)?.toString()?.trim().orEmpty()
                if (s.isNotBlank() && looksLikeNavText(s)) out += s
            }
            return
        }

        for (c in candidates) {
            when (c) {
                is CharSequence -> {
                    val s = c.toString().trim()
                    if (s.isNotBlank() && s.length < 400) out += s
                }
                is String -> if (c.isNotBlank() && c.length < 400) out += c.trim()
            }
        }
    }

    private fun looksLikeNavText(s: String): Boolean {
        val lower = s.lowercase()
        return lower.contains("min") || lower.contains("km") || lower.contains("mi") ||
            lower.contains("eta") || lower.contains("arrive") ||
            Regex("""\d{1,2}:\d{2}""").containsMatchIn(s)
    }

    private fun findNestedRemoteViews(action: Any): List<RemoteViews> {
        val found = mutableListOf<RemoteViews>()
        var clazz: Class<*>? = action.javaClass
        var sweeps = 0
        while (clazz != null && clazz != Any::class.java && sweeps < 4) {
            for (f in clazz.declaredFields) {
                val v = runCatching {
                    f.isAccessible = true
                    f.get(action)
                }.getOrNull() ?: continue
                when (v) {
                    is RemoteViews -> found += v
                    is Array<*> -> v.filterIsInstance<RemoteViews>().forEach { found += it }
                    is ArrayList<*> -> v.filterIsInstance<RemoteViews>().forEach { found += it }
                }
            }
            clazz = clazz.superclass
            sweeps++
        }
        return found
    }

    private fun readField(obj: Any, name: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            val v = runCatching {
                clazz!!.getDeclaredField(name).also { it.isAccessible = true }.get(obj)
            }.getOrNull()
            if (v != null) return v
            clazz = clazz.superclass
        }
        return null
    }

    private fun harvestAppliedViews(
        context: Context,
        packageName: String?,
        remoteViews: RemoteViews
    ): List<String> {
        val pkg = packageName ?: return emptyList()
        val mapsCx = runCatching {
            context.createPackageContext(
                pkg,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_RESTRICTED
            )
        }.getOrNull() ?: runCatching {
            context.createPackageContext(pkg, Context.CONTEXT_RESTRICTED)
        }.getOrNull() ?: context

        val host = FrameLayout(context)
        val applied = runCatching { remoteViews.apply(mapsCx, host) }.getOrNull()
            ?: runCatching { remoteViews.apply(context, host) }.getOrNull()
            ?: return emptyList()
        host.addView(applied)
        runCatching { remoteViews.reapply(mapsCx, applied) }
        runCatching { remoteViews.reapply(context, applied) }
        val texts = mutableListOf<String>()
        collectText(host, mapsCx, texts)
        return texts
    }

    private fun collectText(view: View, mapsCx: Context, out: MutableList<String>) {
        when (view) {
            is TextView -> {
                val t = view.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) out += t
                val cd = view.contentDescription?.toString()?.trim().orEmpty()
                if (cd.isNotEmpty()) out += cd
                val name = runCatching {
                    if (view.id > 0) mapsCx.resources.getResourceEntryName(view.id) else null
                }.getOrNull()
                if (name != null && t.isNotEmpty()) {
                    when (name) {
                        "nav_time", "header_text", "lockscreen_eta", "text",
                        "nav_description", "lockscreen_oneliner", "lockscreen_directions",
                        "title", "nav_title", "eta", "distance", "time",
                        "alternate_time", "alternate_distance" -> out += "FIELD:$name=$t"
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
            rawPreview = listOfNotNull(b.rawPreview, a.rawPreview).firstOrNull { it.isNotBlank() }.orEmpty(),
            updatedAtMs = maxOf(a.updatedAtMs, b.updatedAtMs).coerceAtLeast(System.currentTimeMillis())
        )
    }
}
