package com.itvs.connect.ble

/**
 * Latest Google Maps navigation values harvested from the notification listener.
 * No Maps SDK / API key — text parsed from the ongoing nav notification
 * (extras + inflated RemoteViews, since Maps often leaves title/text null).
 */
data class MapsNavSnapshot(
    val etaText: String? = null,
    val remainingDistanceText: String? = null,
    val rawPreview: String = "",
    val updatedAtMs: Long = 0L
) {
    val isFresh: Boolean
        get() = updatedAtMs > 0L &&
            System.currentTimeMillis() - updatedAtMs < STALE_MS

    fun etaOrNa(): String = if (isFresh) etaText?.takeIf { it.isNotBlank() } ?: "N/A" else "N/A"
    fun distanceOrNa(): String =
        if (isFresh) remainingDistanceText?.takeIf { it.isNotBlank() } ?: "N/A" else "N/A"

    companion object {
        const val STALE_MS = 180_000L
        val Empty = MapsNavSnapshot()
    }
}

object MapsNavigationStore {
    @Volatile
    var snapshot: MapsNavSnapshot = MapsNavSnapshot.Empty
        private set

    fun update(etaText: String?, remainingDistanceText: String?, rawPreview: String = "") {
        if (etaText.isNullOrBlank() && remainingDistanceText.isNullOrBlank()) return
        val prev = snapshot
        snapshot = MapsNavSnapshot(
            etaText = etaText?.takeIf { it.isNotBlank() } ?: prev.etaText,
            remainingDistanceText = remainingDistanceText?.takeIf { it.isNotBlank() }
                ?: prev.remainingDistanceText,
            rawPreview = rawPreview.ifBlank { prev.rawPreview },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun update(snap: MapsNavSnapshot) {
        update(snap.etaText, snap.remainingDistanceText, snap.rawPreview)
    }

    fun clear() {
        snapshot = MapsNavSnapshot.Empty
    }
}

/**
 * Parses ETA / remaining distance from Google Maps notification text blobs.
 *
 * Typical Maps `nav_time` / header line looks like:
 * `15 min · 4.2 km · 4:32 PM` (separators vary: · | - — •)
 */
object MapsNavParser {

    private val SPLIT = Regex("""\s*[·•|—–\-]\s*""")

    private val CLOCK_ETA = Regex(
        """\b(\d{1,2}[:.]\d{2}\s*(?:AM|PM|am|pm)?)\b"""
    )
    private val DURATION_HM = Regex(
        """\b(\d{1,2})\s*h(?:r|ours?)?\s*(\d{1,2})\s*m(?:in|ins?)?\b""",
        RegexOption.IGNORE_CASE
    )
    private val DURATION_H_ONLY = Regex(
        """\b(\d{1,2})\s*(?:hr|hrs|hour|hours)\b""",
        RegexOption.IGNORE_CASE
    )
    private val DURATION_MIN = Regex(
        """\b(\d{1,3})\s*(?:min|mins|minute|minutes)\b""",
        RegexOption.IGNORE_CASE
    )
    private val DIST = Regex(
        """\b(\d+(?:[.,]\d+)?)\s*(km|mi|m|kilometers?|kilometres?|miles?|meters?|metres?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ARRIVE_BY = Regex(
        """(?i)(?:arrive(?:\s+by)?|eta)[:\s]+(\d{1,2}[:.]\d{2}\s*(?:AM|PM|am|pm)?)"""
    )

    fun parse(vararg parts: String?): MapsNavSnapshot {
        val cleaned = parts.filterNotNull()
            .map { it.replace('\u00A0', ' ').replace("ETA_HINT", "").trim() }
            .map { line ->
                if (line.startsWith("FIELD:")) line.substringAfter('=', line) else line
            }
            .filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return MapsNavSnapshot.Empty

        val blob = cleaned.joinToString(" · ")
        // Prefer structured "duration · distance · clock" lines when present.
        var eta: String? = null
        var distance: String? = null
        for (line in cleaned) {
            val structured = parseNavTimeLine(line)
            if (structured != null) {
                eta = eta ?: structured.first
                distance = distance ?: structured.second
            }
        }
        eta = eta ?: extractEta(blob)
        distance = distance ?: extractDistance(blob)
        if (eta == null && distance == null) return MapsNavSnapshot.Empty

        return MapsNavSnapshot(
            etaText = eta,
            remainingDistanceText = distance,
            rawPreview = blob.take(200),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Maps often uses: `12 min · 3.4 km · 5:10 PM`
     * @return Pair(eta, distance)
     */
    fun parseNavTimeLine(line: String): Pair<String?, String?>? {
        val parts = line.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        var duration: String? = null
        var distance: String? = null
        var clock: String? = null
        for (part in parts) {
            when {
                duration == null && DURATION_HM.containsMatchIn(part) -> {
                    val m = DURATION_HM.find(part)!!
                    duration = "${m.groupValues[1]}h ${m.groupValues[2]}m"
                }
                duration == null && DURATION_MIN.containsMatchIn(part) -> {
                    duration = "${DURATION_MIN.find(part)!!.groupValues[1]}m"
                }
                duration == null && DURATION_H_ONLY.containsMatchIn(part) -> {
                    duration = "${DURATION_H_ONLY.find(part)!!.groupValues[1]}h"
                }
                distance == null && DIST.containsMatchIn(part) -> {
                    distance = normalizeDistance(DIST.find(part)!!)
                }
                clock == null && CLOCK_ETA.containsMatchIn(part) -> {
                    clock = CLOCK_ETA.find(part)!!.groupValues[1].trim()
                }
            }
        }
        // Prefer arrival clock when present; fall back to remaining duration.
        val eta = clock ?: duration
        if (eta == null && distance == null) return null
        return eta to distance
    }

    private fun extractEta(blob: String): String? {
        ARRIVE_BY.find(blob)?.groupValues?.getOrNull(1)?.let { return it.trim() }
        CLOCK_ETA.find(blob)?.groupValues?.getOrNull(1)?.let { return it.trim() }
        DURATION_HM.find(blob)?.let { m ->
            return "${m.groupValues[1]}h ${m.groupValues[2]}m"
        }
        DURATION_MIN.find(blob)?.groupValues?.getOrNull(1)?.let { return "${it}m" }
        DURATION_H_ONLY.find(blob)?.groupValues?.getOrNull(1)?.let { return "${it}h" }
        return null
    }

    private fun extractDistance(blob: String): String? {
        // Prefer km/mi over bare meters
        var best: Pair<Double, String>? = null
        for (m in DIST.findAll(blob)) {
            val normalized = normalizeDistance(m) ?: continue
            val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val unit = m.groupValues[2].lowercase()
            val km = when {
                unit.startsWith("mi") || unit.startsWith("mile") -> value * 1.60934
                unit == "m" || unit.startsWith("meter") || unit.startsWith("metre") -> value / 1000.0
                else -> value
            }
            if (best == null || km > best.first) best = km to normalized
        }
        return best?.second
    }

    private fun normalizeDistance(m: MatchResult): String? {
        val value = m.groupValues.getOrNull(1)?.replace(',', '.') ?: return null
        val unitRaw = m.groupValues.getOrNull(2)?.lowercase().orEmpty()
        val unit = when {
            unitRaw.startsWith("km") || unitRaw.startsWith("kilometer") ||
                unitRaw.startsWith("kilometre") -> "km"
            unitRaw.startsWith("mi") || unitRaw.startsWith("mile") -> "mi"
            (unitRaw == "m" || unitRaw.startsWith("meter") || unitRaw.startsWith("metre")) &&
                value.toDoubleOrNull()?.let { it >= 100 } == true -> "m"
            else -> return null
        }
        return "$value $unit"
    }

    fun isMapsPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val p = packageName.lowercase()
        return p == "com.google.android.apps.maps" ||
            p == "com.google.android.apps.mapslite" ||
            p.contains("com.google.android.apps.mapslite") ||
            p.endsWith(".apps.maps") ||
            (p.contains("google") && p.contains("maps"))
    }
}
