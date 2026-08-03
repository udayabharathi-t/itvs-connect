package com.itvs.connect.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("5456534d-5647-5341-5342-454e544f5251")
    val WRITE_UUID: UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb")
    val READ_UUID: UUID = UUID.fromString("00005354-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Shared AES-128 key extracted from the Jupiter SmartXonnect protocol. */
    val AES_KEY: ByteArray = byteArrayOf(
        0x7A.toByte(), 0xA3.toByte(), 0x20, 0x4D,
        0x16, 0x1D, 0xB5.toByte(), 0x33,
        0xF4.toByte(), 0xEB.toByte(), 0x20, 0x4F,
        0xBC.toByte(), 0xD7.toByte(), 0x3D, 0xD4.toByte()
    )

    const val START_BYTE: Byte = 0x5B
    const val END_BYTE: Byte = 0xFF.toByte()

    const val AUTH_START_BYTE: Byte = 0x9A.toByte()
    const val AUTH_CHALLENGE_ID: Byte = 0xF2.toByte()
    const val AUTH_DATA_ID: Byte = 0xF1.toByte()
    const val AUTH_REQUEST_TYPE: Byte = 0x50

    const val DATA_ID_MOBILE_DATA: Byte = 0x4A
    const val DATA_ID_USER_ID: Byte = 0x22
    const val DATA_ID_RIDER_NAME: Byte = 0x52
    const val DATA_ID_MESSAGE1: Byte = 0x4C
    const val DATA_ID_MESSAGE2: Byte = 0x63
    const val DATA_ID_CALL_IN: Byte = 0x43
    const val DATA_ID_CALL_OUT: Byte = 0x44

    const val DATA_ID_ODO_FUEL: Int = 0x10
    const val DATA_ID_SERVICE: Int = 0x11
    const val DATA_ID_TELEMETRY_A: Int = 0x18
    const val DATA_ID_ECONOMY: Int = 0x19
    const val DATA_ID_MUSIC: Int = 0x54
    const val DATA_ID_DIALER: Int = 0x6B

    const val HEARTBEAT_INTERVAL_MS = 2_000L
    const val FIND_ME_DURATION_MS = 5_000L
    const val FIND_ME_INTERVAL_MS = 1_000L
    const val WRITE_TIMEOUT_MS = 2_000L
    const val INTER_WRITE_DELAY_MS = 100L
    const val CCCD_DELAY_MS = 600L
    const val POST_AUTH_DELAY_MS = 500L
    const val RX_WATCHDOG_MS = 15_000L
    const val RIDE_END_GRACE_MS = 120_000L
    const val BUTTON_SEQUENCE_RESET_MS = 2_000L
    const val TELEMETRY_PERSIST_MS = 5_000L
    const val CLUSTER_FLASH_MS = 4_000L
}
