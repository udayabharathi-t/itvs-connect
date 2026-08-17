package com.itvs.connect.ble

import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import kotlin.random.Random

object PacketBuilder {

    fun checksum(packet: ByteArray): Byte {
        var sum = 0
        for (i in 0 until 18) {
            sum += packet[i].toInt() and 0xFF
        }
        return (255 - (sum % 256)).toByte()
    }

    fun encryptChallenge(challenge: ByteArray, key: ByteArray = BleConstants.AES_KEY): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(challenge))
        return cipher.doFinal(challenge)
    }

    fun buildAuthResponsePacket(
        challenge: ByteArray,
        key: ByteArray = BleConstants.AES_KEY,
        random: Random = Random.Default
    ): ByteArray {
        val encrypted = encryptChallenge(challenge, key)
        val rr = random.nextInt(1, 15)
        val maxLen = min(16 - rr, 12)
        val ll = if (maxLen > 1) random.nextInt(1, maxLen + 1) else 1
        val packet = ByteArray(20)
        packet[0] = BleConstants.AUTH_START_BYTE
        packet[1] = BleConstants.AUTH_DATA_ID
        packet[2] = BleConstants.AUTH_REQUEST_TYPE
        packet[3] = rr.toByte()
        packet[4] = ll.toByte()
        packet[5] = 0
        System.arraycopy(encrypted, rr, packet, 6, ll)
        packet[19] = BleConstants.END_BYTE
        return packet
    }

    fun buildPingPacket(
        isFindMe: Boolean,
        batteryPercent: Int = 100,
        signalBars: Int = 5,
        calendar: Calendar = Calendar.getInstance()
    ): ByteArray {
        val packet = ByteArray(20)
        var hour = calendar.get(Calendar.HOUR)
        if (hour == 0) hour = 12
        val amPm = if (calendar.get(Calendar.AM_PM) == Calendar.PM) 1 else 0
        val batBars = (batteryPercent / 20).coerceIn(0, 5)
        val sigBars = signalBars.coerceIn(0, 5)

        packet[0] = BleConstants.START_BYTE
        packet[1] = BleConstants.DATA_ID_MOBILE_DATA
        packet[2] = ((sigBars shl 4) or batBars).toByte()
        packet[3] = 0
        packet[4] = 0x41 // ambient temp placeholder (°C + 40)
        packet[5] = 0
        packet[6] = hour.toByte()
        packet[7] = calendar.get(Calendar.MINUTE).toByte()
        packet[8] = calendar.get(Calendar.SECOND).toByte()
        packet[9] = amPm.toByte()
        packet[10] = 0
        packet[11] = 0x04 // LTE placeholder
        packet[12] = calendar.get(Calendar.DAY_OF_MONTH).toByte()
        packet[13] = (calendar.get(Calendar.MONTH) + 1).toByte()
        packet[14] = (calendar.get(Calendar.YEAR) % 100).toByte()
        packet[15] = 0
        packet[16] = 0
        packet[17] = if (isFindMe) 1 else 0
        packet[18] = checksum(packet)
        packet[19] = BleConstants.END_BYTE
        return packet
    }

    fun buildUserIdPacket(): ByteArray {
        val packet = ByteArray(20)
        packet[0] = BleConstants.START_BYTE
        packet[1] = BleConstants.DATA_ID_USER_ID
        packet[2] = 1
        packet[18] = checksum(packet)
        packet[19] = BleConstants.END_BYTE
        return packet
    }

    fun buildRiderNamePacket(name: String = "iTVS"): ByteArray {
        val packet = ByteArray(20)
        packet[0] = BleConstants.START_BYTE
        packet[1] = BleConstants.DATA_ID_RIDER_NAME
        val bytes = name.toByteArray(Charsets.UTF_8).take(16)
        bytes.forEachIndexed { index, b -> packet[index + 2] = b }
        packet[18] = checksum(packet)
        packet[19] = BleConstants.END_BYTE
        return packet
    }

    fun buildMessagePackets(row1: String, row2: String = ""): List<ByteArray> {
        fun rowPacket(dataId: Byte, text: String): ByteArray {
            val packet = ByteArray(20)
            packet[0] = BleConstants.START_BYTE
            packet[1] = dataId
            val bytes = text.toByteArray(Charsets.UTF_8).take(17)
            bytes.forEachIndexed { index, b -> packet[index + 2] = b }
            packet[19] = BleConstants.END_BYTE
            return packet
        }

        val packets = mutableListOf(rowPacket(BleConstants.DATA_ID_MESSAGE1, sanitizeClusterText(row1)))
        if (row2.isNotBlank()) {
            packets += rowPacket(BleConstants.DATA_ID_MESSAGE2, sanitizeClusterText(row2))
        }
        return packets
    }

    /**
     * Native navigation HUD control (`0x5A 0x4E`).
     *
     * Layout (JupiterRideCompanion BleNavigationPacketBuilder):
     * - [2-3] distance to turn (meters)
     * - [4-5] remaining time (minutes)
     * - [6-8] remaining trip distance (meters, 24-bit)
     * - [9] pictogram / maneuver ID
     * - [10] text rows (1)
     * - [11] active flag
     * - [19] end `0xFF` (no checksum)
     */
    fun buildNavigationControlPacket(
        distanceMeters: Int,
        remainingTimeMinutes: Int,
        remainingDistanceMeters: Int,
        maneuverId: Int,
        isActive: Boolean = true
    ): ByteArray {
        val packet = ByteArray(20)
        packet[0] = BleConstants.START_BYTE_NAV_CONTROL
        packet[1] = BleConstants.DATA_ID_NAV_CONTROL

        val turnM = distanceMeters.coerceIn(0, 65_535)
        if (turnM >= 65_535) {
            packet[2] = 0xFF.toByte()
            packet[3] = 0xFF.toByte()
        } else if (turnM <= 255) {
            packet[2] = 0
            packet[3] = turnM.toByte()
        } else {
            packet[2] = ((turnM shr 8) and 0xFF).toByte()
            packet[3] = (turnM and 0xFF).toByte()
        }

        val mins = remainingTimeMinutes.coerceIn(0, 65_535)
        if (mins <= 255) {
            packet[4] = 0
            packet[5] = mins.toByte()
        } else {
            packet[4] = ((mins shr 8) and 0xFF).toByte()
            packet[5] = (mins and 0xFF).toByte()
        }

        val rem = remainingDistanceMeters.coerceAtLeast(0)
        if (rem <= 255) {
            packet[6] = 0
            packet[7] = 0
            packet[8] = rem.toByte()
        } else {
            packet[6] = ((rem shr 16) and 0xFF).toByte()
            packet[7] = ((rem shr 8) and 0xFF).toByte()
            packet[8] = (rem and 0xFF).toByte()
        }

        packet[9] = (maneuverId and 0xFF).toByte()
        packet[10] = 1
        packet[11] = if (isActive) 1 else 0
        packet[19] = BleConstants.END_BYTE
        return packet
    }

    /** Nav text row (`0x5B 0x4F` / `0x50`) — street name or metrics under the native arrow. */
    fun buildNavigationTextPacket(row1: String, row2: String = ""): List<ByteArray> {
        fun rowPacket(dataId: Byte, text: String): ByteArray {
            val packet = ByteArray(20)
            packet[0] = BleConstants.START_BYTE
            packet[1] = dataId
            val bytes = sanitizeClusterText(text).toByteArray(Charsets.UTF_8).take(17)
            bytes.forEachIndexed { index, b -> packet[index + 2] = b }
            packet[19] = BleConstants.END_BYTE
            return packet
        }

        val packets = mutableListOf(rowPacket(BleConstants.DATA_ID_NAV_TEXT1, row1))
        if (row2.isNotBlank()) {
            packets += rowPacket(BleConstants.DATA_ID_NAV_TEXT2, row2)
        }
        return packets
    }

    /**
     * Full nav update: control (pictogram + distances) then optional text rows.
     * Inactive clears the native arrow overlay.
     */
    fun buildNavigationPackets(
        distanceMeters: Int,
        remainingTimeMinutes: Int,
        remainingDistanceMeters: Int,
        maneuverId: Int,
        textRow1: String = "",
        textRow2: String = "",
        isActive: Boolean = true
    ): List<ByteArray> {
        val packets = mutableListOf(
            buildNavigationControlPacket(
                distanceMeters = distanceMeters,
                remainingTimeMinutes = remainingTimeMinutes,
                remainingDistanceMeters = remainingDistanceMeters,
                maneuverId = maneuverId,
                isActive = isActive
            )
        )
        if (isActive && textRow1.isNotBlank()) {
            packets += buildNavigationTextPacket(textRow1, textRow2)
        }
        return packets
    }

    fun sanitizeClusterText(input: String): String =
        input.replace(Regex("[^A-Za-z0-9 .:/]"), "").take(17)
}
