package com.itvs.connect.ble

/**
 * Picks realtime (IFE-like) km/L from economy packets by watching which byte
 * actually changes. Sticky AFE at the documented offset 8 is never used for Live
 * — that is what produced the constant "40" readings on Live and Trip.
 */
object LiveEconomyProbe {

    private const val WINDOW = 24
    /** Documented AFE byte — excluded from Live selection. */
    private const val AFE_OFFSET = 8

    private val windows = Array(11) { ArrayDeque<Int>(WINDOW) }

    @Synchronized
    fun reset() {
        windows.forEach { it.clear() }
    }

    /**
     * Observe one `0x19` payload and return the best live km/L, or null when
     * only a sticky average is present.
     */
    @Synchronized
    fun observe(data: ByteArray): ProbeResult {
        if (data.size < 14) return ProbeResult()
        for (offset in 2..10) {
            val v = data[offset].toInt() and 0xFF
            if (!TelemetryParser.isValidKmL(v)) continue
            val q = windows[offset]
            q.addLast(v)
            while (q.size > WINDOW) q.removeFirst()
        }

        val afe = (data[8].toInt() and 0xFF).takeIf { TelemetryParser.isValidKmL(it) }
        val live = selectLive()
        return ProbeResult(
            liveKmL = live,
            clusterAfeKmL = afe,
            debug = debugSummary()
        )
    }

    private fun selectLive(): Int? {
        data class Cand(val offset: Int, val latest: Int, val distinct: Int, val changes: Int)
        val cands = mutableListOf<Cand>()
        for (offset in 2..10) {
            if (offset == AFE_OFFSET) continue
            val q = windows[offset]
            if (q.isEmpty()) continue
            val distinct = q.toSet().size
            var changes = 0
            var prev: Int? = null
            for (v in q) {
                if (prev != null && prev != v) changes++
                prev = v
            }
            // Need actual movement — a single sticky fill (e.g. b7==40 forever) is not live.
            if (changes >= 1 || distinct >= 2) {
                cands += Cand(offset, q.last(), distinct, changes)
            }
        }
        if (cands.isEmpty()) return null
        return cands.maxWithOrNull(
            compareBy<Cand> { it.changes }
                .thenBy { it.distinct }
                // Prefer documented IFE@7, then@9, then other offsets.
                .thenBy {
                    when (it.offset) {
                        7 -> 3
                        9 -> 2
                        else -> 1
                    }
                }
        )?.latest
    }

    private fun debugSummary(): String {
        return (2..10).mapNotNull { offset ->
            val q = windows[offset]
            if (q.isEmpty()) return@mapNotNull null
            "b$offset=${q.last()}(${q.toSet().size}u)"
        }.joinToString(" ")
    }

    data class ProbeResult(
        val liveKmL: Int? = null,
        val clusterAfeKmL: Int? = null,
        val debug: String = ""
    )
}
