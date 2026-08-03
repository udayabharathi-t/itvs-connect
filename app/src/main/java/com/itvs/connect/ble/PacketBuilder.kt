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

    fun sanitizeClusterText(input: String): String =
        input.replace(Regex("[^A-Za-z0-9 .]"), "").take(17)
}
