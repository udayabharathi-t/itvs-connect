package com.itvs.connect

import com.google.common.truth.Truth.assertThat
import com.itvs.connect.ble.BleConstants
import com.itvs.connect.ble.LiveEconomyProbe
import com.itvs.connect.ble.TelemetryParser
import org.junit.Before
import org.junit.Test

class LiveEconomyProbeTest {

    @Before
    fun reset() {
        LiveEconomyProbe.reset()
    }

    private fun eco(b7: Int, b8: Int, b9: Int = 0): ByteArray {
        val data = ByteArray(20)
        data[0] = BleConstants.START_BYTE
        data[1] = BleConstants.DATA_ID_ECONOMY.toByte()
        data[7] = b7.toByte()
        data[8] = b8.toByte()
        data[9] = b9.toByte()
        data[11] = 0
        data[12] = 50
        return data
    }

    @Test
    fun stickyAfeAloneIsNotLive() {
        repeat(10) {
            val r = LiveEconomyProbe.observe(eco(b7 = 40, b8 = 40))
            assertThat(r.liveKmL).isNull()
            assertThat(r.clusterAfeKmL).isEqualTo(40)
        }
        val parsed = TelemetryParser.parse(eco(40, 40))!!
        assertThat(parsed.liveFuelEconomy).isNull()
        assertThat(parsed.averageFuelEconomy).isEqualTo(40)
    }

    @Test
    fun movingIfeAtByte7BecomesLive() {
        LiveEconomyProbe.observe(eco(b7 = 40, b8 = 40))
        val r = LiveEconomyProbe.observe(eco(b7 = 55, b8 = 40))
        assertThat(r.liveKmL).isEqualTo(55)
        assertThat(r.clusterAfeKmL).isEqualTo(40)
    }

    @Test
    fun ifeDifferingFromAfeParsedAsLiveImmediately() {
        val parsed = TelemetryParser.parse(eco(b7 = 61, b8 = 40))!!
        assertThat(parsed.instantFuelEconomy).isEqualTo(61)
        assertThat(parsed.liveFuelEconomy).isEqualTo(61)
        assertThat(parsed.averageFuelEconomy).isEqualTo(40)
    }

    @Test
    fun movingByte9UsedWhenByte7Sticky() {
        LiveEconomyProbe.observe(eco(b7 = 40, b8 = 40, b9 = 30))
        val r = LiveEconomyProbe.observe(eco(b7 = 40, b8 = 40, b9 = 48))
        assertThat(r.liveKmL).isEqualTo(48)
    }
}
