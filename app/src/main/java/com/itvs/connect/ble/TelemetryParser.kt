package com.itvs.connect.ble

data class TelemetrySnapshot(
    val odometerKm: Double? = null,
    val fuelPercent: Int? = null,
    val fuelBars: Int? = null,
    val serviceReminder: Int? = null,
    val averageFuelEconomy: Int? = null,
    val distanceToEmptyKm: Int? = null,
    val callCommand: Int? = null,
    val musicCommand: MusicCommand? = null,
    val isIgnitionTelemetry: Boolean = false
)

enum class MusicCommand {
    PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN
}

object TelemetryParser {

    fun parse(data: ByteArray): TelemetrySnapshot? {
        if (data.isEmpty()) return null
        val start = data[0]
        if (start == BleConstants.AUTH_START_BYTE) return null
        if (start != BleConstants.START_BYTE && start != 0x5A.toByte()) return null
        if (data.size < 2) return null

        return when (data[1].toInt() and 0xFF) {
            BleConstants.DATA_ID_ODO_FUEL -> parseOdoFuel(data)
            BleConstants.DATA_ID_SERVICE -> parseService(data)
            BleConstants.DATA_ID_TELEMETRY_A -> TelemetrySnapshot(isIgnitionTelemetry = true)
            BleConstants.DATA_ID_ECONOMY -> parseEconomy(data)
            BleConstants.DATA_ID_MUSIC -> parseMusic(data)
            BleConstants.DATA_ID_DIALER -> parseDialer(data)
            else -> null
        }
    }

    fun parseOdoFuel(data: ByteArray): TelemetrySnapshot? {
        if (data.size < 8) return null
        val odoRaw = ((data[3].toInt() and 0xFF) shl 16) or
            ((data[4].toInt() and 0xFF) shl 8) or
            (data[5].toInt() and 0xFF)
        val odometerKm = odoRaw / 10.0
        val fuelBars = data[6].toInt() and 0x0F
        val fuelPercent = when {
            fuelBars > 6 || fuelBars < 1 -> 0
            else -> ((fuelBars - 1) * 20).coerceIn(0, 100)
        }
        val callCommand = if (data.size > 13 && data[13].toInt() != 0) {
            data[13].toInt() and 0xFF
        } else {
            null
        }
        return TelemetrySnapshot(
            odometerKm = odometerKm,
            fuelPercent = fuelPercent,
            fuelBars = fuelBars,
            callCommand = callCommand,
            isIgnitionTelemetry = false
        )
    }

    fun parseService(data: ByteArray): TelemetrySnapshot? {
        if (data.size < 6) return null
        return TelemetrySnapshot(
            serviceReminder = data[4].toInt() and 0xFF,
            isIgnitionTelemetry = true
        )
    }

    fun parseEconomy(data: ByteArray): TelemetrySnapshot? {
        if (data.size < 14) return null
        val afe = data[8].toInt() and 0xFF
        val dte = ((data[11].toInt() and 0xFF) shl 8) or (data[12].toInt() and 0xFF)
        return TelemetrySnapshot(
            averageFuelEconomy = afe,
            distanceToEmptyKm = dte,
            isIgnitionTelemetry = true
        )
    }

    fun parseMusic(data: ByteArray): TelemetrySnapshot? {
        if (data.size < 3) return null
        val command = when (data[2].toInt() and 0xFF) {
            0 -> MusicCommand.PLAY
            1 -> MusicCommand.PAUSE
            2 -> MusicCommand.TOGGLE
            3 -> MusicCommand.NEXT
            4 -> MusicCommand.PREVIOUS
            5 -> MusicCommand.VOLUME_UP
            6 -> MusicCommand.VOLUME_DOWN
            else -> null
        }
        return TelemetrySnapshot(musicCommand = command)
    }

    fun parseDialer(data: ByteArray): TelemetrySnapshot? {
        if (data.size <= 2) return null
        return TelemetrySnapshot(callCommand = data[2].toInt() and 0xFF)
    }

    fun extractAuthChallenge(data: ByteArray): ByteArray? {
        if (data.size >= 2 &&
            data[0] == BleConstants.AUTH_START_BYTE &&
            data[1] == BleConstants.AUTH_CHALLENGE_ID
        ) {
            return when {
                data.size >= 18 -> data.copyOfRange(2, 18)
                data.size >= 16 -> data.copyOfRange(0, 16)
                else -> null
            }
        }
        // Fallback used by the original companion app
        if (data.size >= 16 &&
            data[0] != BleConstants.START_BYTE &&
            data[0] != 0x5A.toByte()
        ) {
            return data.copyOfRange(0, 16)
        }
        return null
    }
}
