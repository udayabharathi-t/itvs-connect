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
    fun navigationControlPacket_matchesReferenceLayout() {
        val packet = PacketBuilder.buildNavigationControlPacket(
            distanceMeters = 200,
            remainingTimeMinutes = 15,
            remainingDistanceMeters = 4200,
            maneuverId = 3,
            isActive = true
        )
        assertThat(packet.size).isEqualTo(20)
        assertThat(packet[0]).isEqualTo(BleConstants.START_BYTE_NAV_CONTROL) // 0x5A
        assertThat(packet[1]).isEqualTo(BleConstants.DATA_ID_NAV_CONTROL) // 0x4E
        assertThat(packet[2]).isEqualTo(0.toByte())
        assertThat(packet[3].toInt() and 0xFF).isEqualTo(200)
        assertThat(packet[4]).isEqualTo(0.toByte())
        assertThat(packet[5].toInt() and 0xFF).isEqualTo(15)
        // 4200 = 0x1068 → bytes 06=0x00, 07=0x10, 08=0x68
        assertThat(packet[6].toInt() and 0xFF).isEqualTo(0x00)
        assertThat(packet[7].toInt() and 0xFF).isEqualTo(0x10)
        assertThat(packet[8].toInt() and 0xFF).isEqualTo(0x68)
        assertThat(packet[9].toInt() and 0xFF).isEqualTo(3)
        assertThat(packet[10]).isEqualTo(1.toByte())
        assertThat(packet[11]).isEqualTo(1.toByte())
        assertThat(packet[19]).isEqualTo(BleConstants.END_BYTE)
    }

    @Test
    fun navigationPackets_includeControlAndText() {
        val packets = PacketBuilder.buildNavigationPackets(
            distanceMeters = 80,
            remainingTimeMinutes = 5,
            remainingDistanceMeters = 900,
            maneuverId = 0,
            textRow1 = "Dest left:",
            textRow2 = "0.9 km",
            isActive = true
        )
        assertThat(packets).hasSize(3)
        assertThat(packets[0][0]).isEqualTo(BleConstants.START_BYTE_NAV_CONTROL)
        assertThat(packets[1][1]).isEqualTo(BleConstants.DATA_ID_NAV_TEXT1)
        assertThat(packets[2][1]).isEqualTo(BleConstants.DATA_ID_NAV_TEXT2)
    }

    @Test
    fun maneuverPictogram_fromInstruction() {
        assertThat(com.itvs.connect.ble.ManeuverPictogram.fromInstruction("Turn left onto NH44"))
            .isEqualTo(0)
        assertThat(com.itvs.connect.ble.ManeuverPictogram.fromInstruction("Turn right"))
            .isEqualTo(3)
        assertThat(com.itvs.connect.ble.ManeuverPictogram.fromInstruction("At the roundabout take the 2nd exit straight"))
            .isEqualTo(68)
        assertThat(com.itvs.connect.ble.ManeuverPictogram.fromInstruction("You have arrived"))
            .isEqualTo(8)
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
        eco[7] = 61 // IFE
        eco[8] = 52 // AFE
        eco[11] = 0x00
        eco[12] = 0x7B // 123
        val ecoSnap = TelemetryParser.parse(eco)!!
        assertThat(ecoSnap.instantFuelEconomy).isEqualTo(61)
        assertThat(ecoSnap.averageFuelEconomy).isEqualTo(52)
        assertThat(ecoSnap.liveFuelEconomy).isEqualTo(61)
        assertThat(ecoSnap.distanceToEmptyKm).isEqualTo(123)
        assertThat(ecoSnap.isIgnitionTelemetry).isTrue()

        val afeOnly = ByteArray(20)
        afeOnly[0] = BleConstants.START_BYTE
        afeOnly[1] = BleConstants.DATA_ID_ECONOMY.toByte()
        afeOnly[7] = 0 // IFE blank / low speed
        afeOnly[8] = 40
        afeOnly[11] = 0x00
        afeOnly[12] = 0x50
        val afeSnap = TelemetryParser.parse(afeOnly)!!
        assertThat(afeSnap.instantFuelEconomy).isNull()
        assertThat(afeSnap.averageFuelEconomy).isEqualTo(40)
        // Sticky AFE must not become Live — that produced constant 40.0 readings.
        assertThat(afeSnap.liveFuelEconomy).isNull()
    }
}
