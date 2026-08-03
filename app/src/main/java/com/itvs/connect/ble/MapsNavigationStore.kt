package com.itvs.connect.ble

/**
 * Latest Google Maps navigation values harvested from the notification listener.
 * No Maps SDK / API key — text parsed from the ongoing nav notification.
 */
data class MapsNavSnapshot(
    val etaText: String? = null,
    val remainingDistanceText: String? = null,
    val updatedAtMs: Long = 0L
) {
    val isFresh: Boolean
        get() = updatedAtMs > 0L &&
            System.currentTimeMillis() - updatedAtMs < STALE_MS

    fun etaOrNa(): String = if (isFresh) etaText?.takeIf { it.isNotBlank() } ?: "N/A" else "N/A"
    fun distanceOrNa(): String =
        if (isFresh) remainingDistanceText?.takeIf { it.isNotBlank() } ?: "N/A" else "N/A"

    companion object {
        const val STALE_MS = 120_000L
        val Empty = MapsNavSnapshot()
    }
}

object MapsNavigationStore {
    @Volatile
    var snapshot: MapsNavSnapshot = MapsNavSnapshot.Empty
        private set

    fun update(etaText: String?, remainingDistanceText: String?) {
        if (etaText.isNullOrBlank() && remainingDistanceText.isNullOrBlank()) return
        val prev = snapshot
        snapshot = MapsNavSnapshot(
            etaText = etaText?.takeIf { it.isNotBlank() } ?: prev.etaText,
            remainingDistanceText = remainingDistanceText?.takeIf { it.isNotBlank() }
                ?: prev.remainingDistanceText,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun clear() {
        snapshot = MapsNavSnapshot.Empty
    }
}

/**
 * Parses ETA / remaining distance from Google Maps notification title/text blobs.
 */
object MapsNavParser {

    private val ETA_PATTERNS = listOf(
        Regex("""\bETA\s*[: ]\s*(\d{1,2}:\d{2}\s*(?:AM|PM|am|pm)?)\b"""),
        Regex("""\b(\d{1,3})\s*(?:min|mins|minute|minutes)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d{1,2})\s*h(?:r|ours?)?\s*(\d{1,2})\s*m""", RegexOption.IGNORE_CASE)
    )

    private val DIST_PATTERNS = listOf(
        Regex("""\b(\d+(?:\.\d+)?)\s*(km|mi|m)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d+(?:\.\d+)?)\s*(kilometers?|miles?|meters?)\b""", RegexOption.IGNORE_CASE)
    )

    fun parse(vararg parts: String?): MapsNavSnapshot {
        val blob = parts.filterNotNull().joinToString(" · ").trim()
        if (blob.isBlank()) return MapsNavSnapshot.Empty

        val eta = extractEta(blob)
        val distance = extractDistance(blob)
        if (eta == null && distance == null) return MapsNavSnapshot.Empty

        return MapsNavSnapshot(
            etaText = eta,
            remainingDistanceText = distance,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun extractEta(blob: String): String? {
        ETA_PATTERNS[0].find(blob)?.groupValues?.getOrNull(1)?.let { return it.trim() }

        ETA_PATTERNS[2].find(blob)?.let { m ->
            val h = m.groupValues[1]
            val min = m.groupValues[2]
            return "${h}h ${min}m"
        }

        ETA_PATTERNS[1].find(blob)?.groupValues?.getOrNull(1)?.let { mins ->
            return "${mins}m"
        }
        return null
    }

    private fun extractDistance(blob: String): String? {
        for (pattern in DIST_PATTERNS) {
            val m = pattern.find(blob) ?: continue
            val value = m.groupValues.getOrNull(1) ?: continue
            val unitRaw = m.groupValues.getOrNull(2)?.lowercase().orEmpty()
            val unit = when {
                unitRaw.startsWith("km") || unitRaw.startsWith("kilometer") -> "km"
                unitRaw.startsWith("mi") || unitRaw.startsWith("mile") -> "mi"
                (unitRaw == "m" || unitRaw.startsWith("meter")) &&
                    value.toDoubleOrNull()?.let { it >= 100 } == true -> "m"
                else -> null
            } ?: continue
            return "$value $unit"
        }
        return null
    }

    fun isMapsPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val p = packageName.lowercase()
        return p.contains("maps") ||
            p == "com.google.android.apps.maps" ||
            p.contains("com.google.android.apps.mapslite")
    }
}
