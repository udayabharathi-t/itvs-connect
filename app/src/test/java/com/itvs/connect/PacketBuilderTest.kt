package com.itvs.connect

import com.google.common.truth.Truth.assertThat
import com.itvs.connect.ble.BleConstants
import com.itvs.connect.ble.PacketBuilder
import com.itvs.connect.ble.TelemetryParser
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

class PacketBuilderTest {

    @Test
    fun pingPacket_hasExpectedFrameAndChecksum() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.AUGUST)
            set(Calendar.DAY_OF_MONTH, 3)
            set(Calendar.HOUR, 10)
            set(Calendar.MINUTE, 15)
            set(Calendar.SECOND, 30)
            set(Calendar.AM_PM, Calendar.AM)
        }
        val packet = PacketBuilder.buildPingPacket(
            isFindMe = true,
            batteryPercent = 80,
            signalBars = 4,
            calendar = cal
        )
        assertThat(packet.size).isEqualTo(20)
        assertThat(packet[0]).isEqualTo(BleConstants.START_BYTE)
        assertThat(packet[1]).isEqualTo(BleConstants.DATA_ID_MOBILE_DATA)
        assertThat(packet[17]).isEqualTo(1.toByte())
        assertThat(packet[19]).isEqualTo(BleConstants.END_BYTE)
        assertThat(packet[18]).isEqualTo(PacketBuilder.checksum(packet.copyOf().also { it[18] = 0 }))
    }

    @Test
    fun authResponse_usesChallengeSlice() {
        val challenge = ByteArray(16) { it.toByte() }
        val packet = PacketBuilder.buildAuthResponsePacket(
            challenge,
            random = Random(42)
        )
        assertThat(packet[0]).isEqualTo(BleConstants.AUTH_START_BYTE)
        assertThat(packet[1]).isEqualTo(BleConstants.AUTH_DATA_ID)
        assertThat(packet[2]).isEqualTo(BleConstants.AUTH_REQUEST_TYPE)
        assertThat(packet[19]).isEqualTo(BleConstants.END_BYTE)
        val rr = packet[3].toInt() and 0xFF
        val ll = packet[4].toInt() and 0xFF
        assertThat(rr).isAtLeast(1)
        assertThat(ll).isAtLeast(1)
    }

    @Test
    fun messagePackets_truncateToSeventeenChars() {
        val packets = PacketBuilder.buildMessagePackets("Hello World From iTVS Connect", "RowTwo")
        assertThat(packets).hasSize(2)
        assertThat(packets[0][1]).isEqualTo(BleConstants.DATA_ID_MESSAGE1)
        assertThat(packets[1][1]).isEqualTo(BleConstants.DATA_ID_MESSAGE2)
    }

    @Test
    fun telemetryParser_readsOdoFuelEconomy() {
        val odo = ByteArray(20)
        odo[0] = BleConstants.START_BYTE
        odo[1] = BleConstants.DATA_ID_ODO_FUEL.toByte()
        // 12345.6 km => raw 123456 = 0x01E240
        odo[3] = 0x01
        odo[4] = 0xE2.toByte()
        odo[5] = 0x40
        odo[6] = 0x04 // 4 bars => 60%
        val snap = TelemetryParser.parse(odo)!!
        assertThat(snap.odometerKm).isWithin(0.01).of(12345.6)
        assertThat(snap.fuelPercent).isEqualTo(60)

        val eco = ByteArray(20)
        eco[0] = BleConstants.START_BYTE
        eco[1] = BleConstants.DATA_ID_ECONOMY.toByte()
        eco[8] = 52
        eco[11] = 0x00
        eco[12] = 0x7B // 123
        val ecoSnap = TelemetryParser.parse(eco)!!
        assertThat(ecoSnap.averageFuelEconomy).isEqualTo(52)
        assertThat(ecoSnap.distanceToEmptyKm).isEqualTo(123)
        assertThat(ecoSnap.isIgnitionTelemetry).isTrue()
    }
}
