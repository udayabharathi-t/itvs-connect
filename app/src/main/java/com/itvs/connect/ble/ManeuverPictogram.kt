package com.itvs.connect.ble

/**
 * Maps Google Maps turn instructions to TVS Jupiter cluster pictogram bytes.
 *
 * Byte table from JupiterRideCompanion [NAVIGATION_ARCHITECTURE.md](https://github.com/overclock98/JupiterRideCompanion/blob/main/NAVIGATION_ARCHITECTURE.md).
 * India LHT roundabouts (65–71). Text keyword fallback only (no icon-hash calibration yet).
 */
object ManeuverPictogram {
    const val TURN_LEFT = 0
    const val SLIGHT_LEFT = 2
    const val TURN_RIGHT = 3
    const val SLIGHT_RIGHT = 5
    const val U_TURN_LEFT = 6
    const val STRAIGHT = 7
    const val ARRIVE = 8
    const val FORK_LEFT = 15
    const val FORK_RIGHT = 16
    const val KEEP_LEFT = 17
    const val KEEP_RIGHT = 18
    const val EXIT_LEFT = 19
    const val EXIT_RIGHT = 20

    // India / LHT roundabouts (clockwise)
    const val RABT_SLIGHT_LEFT = 65
    const val RABT_SHARP_LEFT = 66
    const val RABT_SLIGHT_RIGHT = 67
    const val RABT_STRAIGHT = 68
    const val RABT_RIGHT = 69
    const val RABT_SHARP_RIGHT = 70
    const val RABT_U_TURN = 71

    /**
     * Resolve a cluster pictogram ID from Maps instruction / icon contentDescription text.
     * Default: go straight (7).
     */
    fun fromInstruction(directionText: String?): Int {
        if (directionText.isNullOrBlank()) return STRAIGHT
        val text = directionText.lowercase()

        if ("arrived" in text || "destination" in text || "arrive" in text) return ARRIVE

        if ("roundabout" in text || "rotary" in text) {
            return when {
                "straight" in text -> RABT_STRAIGHT
                "sharp left" in text -> RABT_SHARP_LEFT
                "slight left" in text -> RABT_SLIGHT_LEFT
                "left" in text -> RABT_SHARP_LEFT
                "sharp right" in text -> RABT_SHARP_RIGHT
                "slight right" in text -> RABT_SLIGHT_RIGHT
                "right" in text -> RABT_RIGHT
                "u-turn" in text || "u turn" in text || "uturn" in text -> RABT_U_TURN
                else -> RABT_STRAIGHT
            }
        }

        if ("u-turn" in text || "u turn" in text || "uturn" in text) return U_TURN_LEFT

        if ("exit" in text && "left" in text) return EXIT_LEFT
        if ("exit" in text && "right" in text) return EXIT_RIGHT
        if ("fork" in text && "left" in text) return FORK_LEFT
        if ("fork" in text && "right" in text) return FORK_RIGHT
        if ("keep left" in text || "bear left" in text) return KEEP_LEFT
        if ("keep right" in text || "bear right" in text) return KEEP_RIGHT
        if ("sharp left" in text) return TURN_LEFT
        if ("sharp right" in text) return TURN_RIGHT
        if ("slight left" in text) return SLIGHT_LEFT
        if ("slight right" in text) return SLIGHT_RIGHT
        if ("left" in text) return TURN_LEFT
        if ("right" in text) return TURN_RIGHT
        if ("straight" in text || "continue" in text || "head" in text) return STRAIGHT
        return STRAIGHT
    }
}
