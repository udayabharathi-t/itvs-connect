package com.itvs.connect.ble

enum class ButtonAction(val displayName: String) {
    TOGGLE_PLAYBACK("Play / Pause"),
    NEXT_TRACK("Next Track"),
    PREVIOUS_TRACK("Previous Track"),
    VOLUME_UP("Volume Up"),
    VOLUME_DOWN("Volume Down"),
    GOOGLE_ASSISTANT("Google Assistant"),
    SPEED_DIAL("Speed Dial"),
    DO_NOTHING("Do Nothing");

    companion object {
        fun fromName(name: String?): ButtonAction =
            entries.firstOrNull { it.name == name } ?: TOGGLE_PLAYBACK
    }
}

enum class CallGesture(val displayName: String) {
    DISABLE("Disabled"),
    SINGLE_PRESS("Single tap"),
    DOUBLE_PRESS("Double tap"),
    TRIPLE_PRESS("Triple tap"),
    LONG_PRESS("Long press");

    companion object {
        fun fromName(name: String?): CallGesture =
            entries.firstOrNull { it.name == name } ?: DISABLE
    }
}

data class ButtonPressEvent(val durationMs: Long)

data class DiscoveredDevice(
    val name: String,
    val mac: String,
    val rssi: Int,
    val likelyScooter: Boolean
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Scanning : ConnectionState()
    data object Connecting : ConnectionState()
    data object Authenticating : ConnectionState()
    data class Connected(val deviceName: String, val mac: String) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}
