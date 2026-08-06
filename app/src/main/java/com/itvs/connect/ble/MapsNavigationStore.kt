package com.itvs.connect.ble

/**
 * Latest Google Maps navigation values harvested from the notification listener.
 * Structured fields follow GMapsParser (nav_title / nav_time / …); no Maps SDK.
 */
data class MapsNavSnapshot(
    /** Distance to the next maneuver, e.g. `200 m` / `0.3 km`. */
    val nextTurnDistanceText: String? = null,
    /** Next-turn distance in meters for approach lock (<=200). */
    val nextTurnDistanceMeters: Int? = null,
    /** Optional turn instruction text. */
    val nextTurnInstruction: String? = null,
    /** Remaining distance to destination. */
    val remainingDistanceText: String? = null,
    /** Time to reach destination (duration preferred), e.g. `15m`. */
    val timeToDestinationText: String? = null,
    /** Arrival clock when available, e.g. `4:32 PM`. */
    val etaClockText: String? = null,
    val rawPreview: String = "",
    val updatedAtMs: Long = 0L
) {
    /** Back-compat alias used by older HUD pages / More debug. */
    val etaText: String?
        get() = timeToDestinationText ?: etaClockText

    val isFresh: Boolean
        get() = updatedAtMs > 0L &&
            System.currentTimeMillis() - updatedAtMs < STALE_MS

    val hasNavData: Boolean
        get() = nextTurnDistanceText != null ||
            remainingDistanceText != null ||
            timeToDestinationText != null ||
            etaClockText != null

    val isNavigating: Boolean
        get() = isFresh && hasNavData

    /** True when next turn is within [APPROACH_LOCK_METERS]. */
    val isApproachLock: Boolean
        get() = isNavigating &&
            (nextTurnDistanceMeters ?: Int.MAX_VALUE) <= APPROACH_LOCK_METERS

    fun etaOrNa(): String =
        if (isFresh) timeToDestinationText?.takeIf { it.isNotBlank() }
            ?: etaClockText?.takeIf { it.isNotBlank() }
            ?: "N/A"
        else "N/A"

    fun distanceOrNa(): String =
        if (isFresh) remainingDistanceText?.takeIf { it.isNotBlank() } ?: "N/A" else "N/A"

    fun nextTurnOrNa(): String =
        if (isFresh) nextTurnDistanceText?.takeIf { it.isNotBlank() } ?: "N/A" else "N/A"

    companion object {
        const val STALE_MS = 180_000L
        const val APPROACH_LOCK_METERS = 200
        val Empty = MapsNavSnapshot()
    }
}

object MapsNavigationStore {
    @Volatile
    var snapshot: MapsNavSnapshot = MapsNavSnapshot.Empty
        private set

    private val _updates = kotlinx.coroutines.flow.MutableStateFlow(MapsNavSnapshot.Empty)
    val updates: kotlinx.coroutines.flow.StateFlow<MapsNavSnapshot> = _updates

    fun update(snap: MapsNavSnapshot) {
        if (!snap.hasNavData) return
        val prev = snapshot
        val next = MapsNavSnapshot(
            nextTurnDistanceText = snap.nextTurnDistanceText ?: prev.nextTurnDistanceText,
            nextTurnDistanceMeters = snap.nextTurnDistanceMeters ?: prev.nextTurnDistanceMeters,
            nextTurnInstruction = snap.nextTurnInstruction ?: prev.nextTurnInstruction,
            remainingDistanceText = snap.remainingDistanceText ?: prev.remainingDistanceText,
            timeToDestinationText = snap.timeToDestinationText ?: prev.timeToDestinationText,
            etaClockText = snap.etaClockText ?: prev.etaClockText,
            rawPreview = snap.rawPreview.ifBlank { prev.rawPreview },
            updatedAtMs = System.currentTimeMillis()
        )
        snapshot = next
        _updates.value = next
    }

    fun clear() {
        snapshot = MapsNavSnapshot.Empty
        _updates.value = MapsNavSnapshot.Empty
    }
}

/**
 * Parses ETA / remaining / next-turn distances from Google Maps notification text.
 *
 * Prefers GMapsParser field names (`nav_title`, `nav_time`, …). Falls back to
 * blob regex for OEM / layout variants.
 */
object MapsNavParser {

    private val SPLIT = Regex("""\s*[·•|—–\-/]\s*""")
    /** GMapsParser-style: spaces + punctuation + spaces. */
    private val SPLIT_GMAPS = Regex("""\p{Space}+\p{Punct}+\p{Space}+""")

    private val CLOCK_ETA = Regex(
        """\b(\d{1,2}[:.]\d{2}\s*(?:AM|PM|am|pm|ص|م)?)\b"""
    )
    private val DURATION_HM = Regex(
        """\b(\d{1,2})\s*h(?:r|rs|ours?)?\s*(\d{1,2})\s*m(?:in|ins?)?\b""",
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
    private val DURATION_MIN_COMPACT = Regex(
        """\b(\d{1,3})(?:min|mins)\b""",
        RegexOption.IGNORE_CASE
    )
    private val DIST = Regex(
        """\b(\d+(?:[.,]\d+)?)\s*(km|mi|m|ft|yd|kilometers?|kilometres?|miles?|meters?|metres?|feet|yards?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val DIST_COMPACT = Regex(
        """\b(\d+(?:[.,]\d+)?)(km|mi|m|ft)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ARRIVE_BY = Regex(
        """(?i)(?:arrive(?:\s+by)?|eta|arrival)[:\s]*(\d{1,2}[:.]\d{2}\s*(?:AM|PM|am|pm)?)"""
    )

    /**
     * Build a snapshot from GMapsParser-style resource entry names → text.
     */
    fun fromGmapsFields(fields: Map<String, String>): MapsNavSnapshot {
        var nextTurnDist: String? = null
        var nextTurnInstr: String? = null
        var remaining: String? = null
        var duration: String? = null
        var clock: String? = null

        fields["nav_title"]?.let { nextTurnDist = normalizeDistanceLabel(it) ?: it.trim() }
        fields["nav_description"]?.let { nextTurnInstr = it.trim().takeIf { t -> t.isNotBlank() } }

        val timeLine = fields["nav_time"] ?: fields["header_text"]
        if (timeLine != null) {
            parseNavTimeTriple(timeLine)?.let { (dur, dist, clk) ->
                duration = duration ?: dur
                remaining = remaining ?: dist
                clock = clock ?: clk
            }
        }

        val lockDir = fields["lockscreen_directions"] ?: fields["title"]
        if (lockDir != null && nextTurnDist == null) {
            parseLockscreenDirections(lockDir)?.let { (dist, instr) ->
                nextTurnDist = dist
                nextTurnInstr = nextTurnInstr ?: instr
            }
        }
        fields["lockscreen_oneliner"]?.let {
            if (nextTurnInstr == null) nextTurnInstr = it.trim()
        }

        val lockEta = fields["lockscreen_eta"] ?: fields["text"]
        if (lockEta != null) {
            parseNavTimeTriple(lockEta)?.let { (dur, dist, clk) ->
                duration = duration ?: dur
                remaining = remaining ?: dist
                clock = clock ?: clk
            }
            if (duration == null && clock == null) {
                extractDuration(lockEta)?.let { duration = it }
                CLOCK_ETA.find(lockEta)?.groupValues?.getOrNull(1)?.let { clock = it.trim() }
            }
        }

        if (nextTurnDist == null && remaining == null && duration == null && clock == null) {
            return MapsNavSnapshot.Empty
        }

        val preview = fields.entries.joinToString(" | ") { "${it.key}=${it.value}" }.take(240)
        return MapsNavSnapshot(
            nextTurnDistanceText = nextTurnDist,
            nextTurnDistanceMeters = nextTurnDist?.let { distanceToMeters(it) },
            nextTurnInstruction = nextTurnInstr,
            remainingDistanceText = remaining,
            timeToDestinationText = duration,
            etaClockText = clock,
            rawPreview = preview,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun parse(vararg parts: String?): MapsNavSnapshot {
        val cleaned = parts.filterNotNull()
            .map { it.replace('\u00A0', ' ').trim() }
            .map { line ->
                if (line.startsWith("FIELD:")) line.substringAfter('=', line) else line
            }
            .filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return MapsNavSnapshot.Empty

        // Promote FIELD:name=value lines into the GMaps field map when present.
        val fields = linkedMapOf<String, String>()
        val plain = mutableListOf<String>()
        for (raw in parts.filterNotNull()) {
            val t = raw.replace('\u00A0', ' ').trim()
            if (t.startsWith("FIELD:") && t.contains('=')) {
                val name = t.removePrefix("FIELD:").substringBefore('=')
                val value = t.substringAfter('=')
                if (name.isNotBlank() && value.isNotBlank()) fields[name] = value
            } else if (t.isNotBlank()) {
                plain += t
            }
        }
        val fromFields = fromGmapsFields(fields)
        if (fromFields.hasNavData) {
            // Merge any extra blob info for missing pieces.
            if (fromFields.remainingDistanceText != null &&
                fromFields.timeToDestinationText != null &&
                fromFields.nextTurnDistanceText != null
            ) {
                return fromFields
            }
        }

        val blob = cleaned.joinToString(" · ")
        var duration: String? = fromFields.timeToDestinationText
        var remaining: String? = fromFields.remainingDistanceText
        var clock: String? = fromFields.etaClockText
        var nextTurn: String? = fromFields.nextTurnDistanceText

        for (line in cleaned) {
            parseNavTimeTriple(line)?.let { (dur, dist, clk) ->
                duration = duration ?: dur
                remaining = remaining ?: dist
                clock = clock ?: clk
            }
        }
        duration = duration ?: extractDuration(blob)
        remaining = remaining ?: extractDistance(blob)
        clock = clock ?: CLOCK_ETA.find(blob)?.groupValues?.getOrNull(1)?.trim()
            ?: ARRIVE_BY.find(blob)?.groupValues?.getOrNull(1)?.trim()

        // Heuristic: a short lone distance line (e.g. "200 m") is often next-turn.
        if (nextTurn == null) {
            for (line in plain) {
                val onlyDist = normalizeDistanceLabel(line)
                if (onlyDist != null && line.length <= 12) {
                    nextTurn = onlyDist
                    break
                }
            }
        }

        if (nextTurn == null && remaining == null && duration == null && clock == null) {
            return MapsNavSnapshot.Empty
        }

        return MapsNavSnapshot(
            nextTurnDistanceText = nextTurn,
            nextTurnDistanceMeters = nextTurn?.let { distanceToMeters(it) },
            nextTurnInstruction = fromFields.nextTurnInstruction,
            remainingDistanceText = remaining,
            timeToDestinationText = duration,
            etaClockText = clock,
            rawPreview = blob.take(200),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * `12 min · 3.4 km · 5:10 PM` → (duration, remainingDistance, clock)
     *
     * Prefers GMapsParser positional split (duration, distance, eta) when the line
     * has exactly three separator parts; otherwise classifies each token.
     */
    fun parseNavTimeTriple(line: String): Triple<String?, String?, String?>? {
        // Prefer explicit Maps separators (· • | — - /); GMaps space+punct+space is fallback.
        val explicit = line.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val parts = if (explicit.size >= 2) {
            explicit
        } else {
            line.split(SPLIT_GMAPS).map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (parts.size < 2) return null

        // Classic Maps nav_time: exactly 3 parts in fixed order (GMapsParser).
        if (parts.size >= 3) {
            val duration = formatDurationPart(parts[0])
            val distance = normalizeDistanceLabel(parts[1])
            val clock = CLOCK_ETA.find(parts[2])?.groupValues?.getOrNull(1)?.trim()
            if (duration != null || distance != null || clock != null) {
                return Triple(duration, distance, clock)
            }
        }

        var duration: String? = null
        var distance: String? = null
        var clock: String? = null
        for (part in parts) {
            when {
                duration == null && looksLikeDuration(part) -> duration = formatDurationPart(part)
                distance == null && looksLikeDistance(part) ->
                    distance = normalizeDistanceLabel(part)
                clock == null && CLOCK_ETA.containsMatchIn(part) ->
                    clock = CLOCK_ETA.find(part)!!.groupValues[1].trim()
            }
        }
        if (duration == null && distance == null && clock == null) return null
        return Triple(duration, distance, clock)
    }

    /** Back-compat for older tests: Pair(eta, distance) preferring clock then duration. */
    fun parseNavTimeLine(line: String): Pair<String?, String?>? {
        val t = parseNavTimeTriple(line) ?: return null
        val eta = t.third ?: t.first
        if (eta == null && t.second == null) return null
        return eta to t.second
    }

    private fun parseLockscreenDirections(text: String): Pair<String?, String?>? {
        val parts = text.split(SPLIT_GMAPS).map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { text.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() } }
        if (parts.isEmpty()) return null
        if (parts.size == 1) return null to parts[0]
        val dist = normalizeDistanceLabel(parts.first()) ?: return null to text
        val instr = parts.drop(1).joinToString(" · ")
        return dist to instr
    }

    private fun looksLikeDuration(part: String): Boolean =
        DURATION_HM.containsMatchIn(part) ||
            DURATION_MIN.containsMatchIn(part) ||
            DURATION_MIN_COMPACT.containsMatchIn(part) ||
            DURATION_H_ONLY.containsMatchIn(part)

    private fun looksLikeDistance(part: String): Boolean =
        DIST.containsMatchIn(part) || DIST_COMPACT.containsMatchIn(part)

    private fun formatDurationPart(part: String): String? {
        DURATION_HM.find(part)?.let { m ->
            return "${m.groupValues[1]}h ${m.groupValues[2]}m"
        }
        DURATION_MIN.find(part)?.groupValues?.getOrNull(1)?.let { return "${it}m" }
        DURATION_MIN_COMPACT.find(part)?.groupValues?.getOrNull(1)?.let { return "${it}m" }
        DURATION_H_ONLY.find(part)?.groupValues?.getOrNull(1)?.let { return "${it}h" }
        return null
    }

    private fun extractDuration(blob: String): String? = formatDurationPart(blob)

    private fun extractDistance(blob: String): String? {
        var best: Pair<Double, String>? = null
        val matches = DIST.findAll(blob) + DIST_COMPACT.findAll(blob)
        for (m in matches) {
            val normalized = normalizeDistanceMatch(m) ?: continue
            val meters = distanceToMeters(normalized) ?: continue
            if (best == null || meters > best.first) best = meters.toDouble() to normalized
        }
        return best?.second
    }

    fun normalizeDistanceLabel(raw: String): String? {
        val m = DIST.find(raw) ?: DIST_COMPACT.find(raw) ?: return null
        return normalizeDistanceMatch(m)
    }

    private fun normalizeDistanceMatch(m: MatchResult): String? {
        val value = m.groupValues.getOrNull(1)?.replace(',', '.') ?: return null
        val unitRaw = m.groupValues.getOrNull(2)?.lowercase().orEmpty()
        val unit = when {
            unitRaw.startsWith("km") || unitRaw.startsWith("kilometer") ||
                unitRaw.startsWith("kilometre") -> "km"
            unitRaw.startsWith("mi") || unitRaw.startsWith("mile") -> "mi"
            unitRaw.startsWith("ft") || unitRaw.startsWith("feet") || unitRaw == "foot" -> "ft"
            unitRaw.startsWith("yd") || unitRaw.startsWith("yard") -> "yd"
            unitRaw == "m" || unitRaw.startsWith("meter") || unitRaw.startsWith("metre") -> "m"
            else -> return null
        }
        // Drop tiny meter noise that is usually UI chrome, unless >= 1.
        if (unit == "m" && value.toDoubleOrNull()?.let { it < 1 } == true) return null
        return "$value $unit"
    }

    /** Convert a normalized distance label to meters (for approach lock). */
    fun distanceToMeters(label: String): Int? {
        val m = DIST.find(label) ?: DIST_COMPACT.find(label) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val meters = when {
            unit.startsWith("km") || unit.startsWith("kilometer") || unit.startsWith("kilometre") ->
                value * 1000.0
            unit.startsWith("mi") || unit.startsWith("mile") -> value * 1609.34
            unit.startsWith("ft") || unit.startsWith("feet") || unit == "foot" -> value * 0.3048
            unit.startsWith("yd") || unit.startsWith("yard") -> value * 0.9144
            unit == "m" || unit.startsWith("meter") || unit.startsWith("metre") -> value
            else -> return null
        }
        return meters.roundToIntSafe()
    }

    private fun Double.roundToIntSafe(): Int =
        if (this.isNaN() || this.isInfinite()) 0 else kotlin.math.round(this).toInt().coerceAtLeast(0)

    fun isMapsPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val p = packageName.lowercase()
        return p == "com.google.android.apps.maps" ||
            p == "com.google.android.apps.mapslite" ||
            p.contains("com.google.android.apps.mapslite") ||
            p.endsWith(".apps.maps") ||
            (p.contains("google") && p.contains("maps"))
    }

    /** Prefer the ongoing Maps TBT notification (GMapsParser uses id == 1). */
    fun isPreferredMapsNavNotification(sbn: android.service.notification.StatusBarNotification): Boolean {
        if (!isMapsPackage(sbn.packageName)) return false
        if (!sbn.isOngoing) return false
        return true
    }
}
