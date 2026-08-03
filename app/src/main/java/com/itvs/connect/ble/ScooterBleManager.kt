package com.itvs.connect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.BatteryManager
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
class ScooterBleManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var readChar: BluetoothGattCharacteristic? = null
    private var writeAck: CompletableDeferred<Boolean>? = null

    private var heartbeatJob: Job? = null
    private var findMeJob: Job? = null
    private var watchdogJob: Job? = null
    private var scanCallback: ScanCallback? = null

    private var lastRxAt = 0L
    private var lastPacketAt = 0L
    private var consecutive10 = 0
    private var buttonHeld = false
    private var buttonDownTime = 0L
    private var hasSeenNormalCycle = false
    private var lastTelemetryPersistAt = 0L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _isTelemetryActive = MutableStateFlow(false)
    val isTelemetryActive: StateFlow<Boolean> = _isTelemetryActive.asStateFlow()

    private val _odometer = MutableStateFlow(0.0)
    val odometer: StateFlow<Double> = _odometer.asStateFlow()

    private val _fuelLevel = MutableStateFlow(0)
    val fuelLevel: StateFlow<Int> = _fuelLevel.asStateFlow()

    private val _averageFuelEconomy = MutableStateFlow(0)
    val averageFuelEconomy: StateFlow<Int> = _averageFuelEconomy.asStateFlow()

    private val _distanceToEmpty = MutableStateFlow(0)
    val distanceToEmpty: StateFlow<Int> = _distanceToEmpty.asStateFlow()

    private val _serviceReminder = MutableStateFlow(0)
    val serviceReminder: StateFlow<Int> = _serviceReminder.asStateFlow()

    private val _buttonHold = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val buttonHold: SharedFlow<Unit> = _buttonHold.asSharedFlow()

    private val _buttonRelease = MutableSharedFlow<ButtonPressEvent>(extraBufferCapacity = 8)
    val buttonRelease: SharedFlow<ButtonPressEvent> = _buttonRelease.asSharedFlow()

    private val _musicCommands = MutableSharedFlow<MusicCommand>(extraBufferCapacity = 8)
    val musicCommands: SharedFlow<MusicCommand> = _musicCommands.asSharedFlow()

    private val _callCommands = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val callCommands: SharedFlow<Int> = _callCommands.asSharedFlow()

    private val _telemetryPersisted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val telemetryPersisted: SharedFlow<Unit> = _telemetryPersisted.asSharedFlow()

    var riderName: String = "iTVS"

    fun startScan(preferredMac: String? = null) {
        val bluetoothAdapter = adapter ?: return
        if (!bluetoothAdapter.isEnabled) return
        stopScan()
        _connectionState.value = ConnectionState.Scanning

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (!preferredMac.isNullOrBlank() &&
                    !device.address.equals(preferredMac, ignoreCase = true)
                ) {
                    return
                }
                stopScan()
                connect(device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                _connectionState.value = ConnectionState.Disconnected
            }
        }
        scanCallback = callback
        bluetoothAdapter.bluetoothLeScanner?.startScan(filters, settings, callback)

        // Also try direct autoConnect if MAC known
        if (!preferredMac.isNullOrBlank()) {
            val device = bluetoothAdapter.getRemoteDevice(preferredMac)
            connect(device, autoConnect = true)
        }
    }

    fun stopScan() {
        scanCallback?.let {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(it) }
        }
        scanCallback = null
    }

    fun connect(device: BluetoothDevice, autoConnect: Boolean = false) {
        stopScan()
        _connectionState.value = ConnectionState.Connecting
        gatt?.close()
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun connectMac(mac: String, autoConnect: Boolean = true) {
        val device = adapter?.getRemoteDevice(mac) ?: return
        connect(device, autoConnect)
    }

    fun disconnect() {
        stopScan()
        heartbeatJob?.cancel()
        findMeJob?.cancel()
        watchdogJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        readChar = null
        hasSeenNormalCycle = false
        _isTelemetryActive.value = false
        _connectionState.value = ConnectionState.Disconnected
    }

    fun findMe() {
        if (_isPinging.value) return
        findMeJob?.cancel()
        findMeJob = scope.launch {
            _isPinging.value = true
            val endAt = System.currentTimeMillis() + BleConstants.FIND_ME_DURATION_MS
            while (isActive && System.currentTimeMillis() < endAt) {
                safeWrite(PacketBuilder.buildPingPacket(isFindMe = true, batteryPercent = phoneBattery()))
                delay(BleConstants.FIND_ME_INTERVAL_MS)
            }
            _isPinging.value = false
        }
    }

    fun sendClusterMessage(row1: String, row2: String = "") {
        scope.launch {
            PacketBuilder.buildMessagePackets(row1, row2).forEach { packet ->
                safeWrite(packet)
                delay(BleConstants.INTER_WRITE_DELAY_MS)
            }
        }
    }

    fun sendCallUpdate(name: String, incoming: Boolean) {
        val row1 = if (incoming) "Incoming Call" else "Calling..."
        sendClusterMessage(row1, name)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.Authenticating
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                heartbeatJob?.cancel()
                findMeJob?.cancel()
                watchdogJob?.cancel()
                hasSeenNormalCycle = false
                _isTelemetryActive.value = false
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return
            writeChar = service.getCharacteristic(BleConstants.WRITE_UUID)
            readChar = service.getCharacteristic(BleConstants.READ_UUID)
            val notifyChar = readChar ?: return
            gatt.setCharacteristicNotification(notifyChar, true)
            scope.launch {
                delay(BleConstants.CCCD_DELAY_MS)
                val cccd = notifyChar.getDescriptor(BleConstants.CCCD_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
                startHeartbeat()
                startWatchdog()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            lastRxAt = System.currentTimeMillis()
            handleIncoming(data)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
            writeAck = null
        }
    }

    private fun handleIncoming(data: ByteArray) {
        TelemetryParser.extractAuthChallenge(data)?.let { challenge ->
            scope.launch { handleAuth(challenge) }
            return
        }

        val now = System.currentTimeMillis()
        val gap = if (lastPacketAt == 0L) 0L else now - lastPacketAt
        lastPacketAt = now

        if (hasSeenNormalCycle && gap > BleConstants.BUTTON_SEQUENCE_RESET_MS) {
            consecutive10 = 0
            if (buttonHeld) {
                buttonHeld = false
            }
            hasSeenNormalCycle = false
            _isTelemetryActive.value = false
        }

        val dataId = if (data.size >= 2) data[1].toInt() and 0xFF else -1

        // Button hold detection: silence-gap via consecutive 0x10 packets
        if (dataId == BleConstants.DATA_ID_ODO_FUEL) {
            consecutive10++
            if (!buttonHeld && hasSeenNormalCycle && consecutive10 >= 3) {
                buttonHeld = true
                buttonDownTime = now - (consecutive10 - 1) * 20L
                _buttonHold.tryEmit(Unit)
            }
        } else if (dataId == BleConstants.DATA_ID_SERVICE ||
            dataId == BleConstants.DATA_ID_TELEMETRY_A ||
            dataId == BleConstants.DATA_ID_ECONOMY
        ) {
            if (buttonHeld) {
                val duration = now - buttonDownTime
                buttonHeld = false
                consecutive10 = 0
                _buttonRelease.tryEmit(ButtonPressEvent(duration))
            } else {
                consecutive10 = 0
            }
            hasSeenNormalCycle = true
            _isTelemetryActive.value = true
        }

        val snapshot = TelemetryParser.parse(data) ?: return
        if (snapshot.isIgnitionTelemetry) {
            hasSeenNormalCycle = true
            _isTelemetryActive.value = true
        }
        snapshot.odometerKm?.let { _odometer.value = it }
        snapshot.fuelPercent?.let { _fuelLevel.value = it }
        snapshot.averageFuelEconomy?.let { _averageFuelEconomy.value = it }
        snapshot.distanceToEmptyKm?.let { _distanceToEmpty.value = it }
        snapshot.serviceReminder?.let {
            if (_serviceReminder.value != it) _serviceReminder.value = it
        }
        snapshot.musicCommand?.let { _musicCommands.tryEmit(it) }
        snapshot.callCommand?.let { _callCommands.tryEmit(it) }

        if (now - lastTelemetryPersistAt > BleConstants.TELEMETRY_PERSIST_MS) {
            lastTelemetryPersistAt = now
            _telemetryPersisted.tryEmit(Unit)
        }
    }

    private suspend fun handleAuth(challenge: ByteArray) {
        _connectionState.value = ConnectionState.Authenticating
        val ok = safeWrite(PacketBuilder.buildAuthResponsePacket(challenge))
        if (!ok) return
        delay(BleConstants.POST_AUTH_DELAY_MS)
        safeWrite(PacketBuilder.buildUserIdPacket())
        delay(BleConstants.POST_AUTH_DELAY_MS)
        safeWrite(PacketBuilder.buildRiderNamePacket(riderName))
        delay(BleConstants.POST_AUTH_DELAY_MS)
        val device = gatt?.device
        _connectionState.value = ConnectionState.Connected(
            deviceName = device?.name ?: "TVS Scooter",
            mac = device?.address.orEmpty()
        )
        startHeartbeat()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                if (!_isPinging.value && writeChar != null) {
                    safeWrite(
                        PacketBuilder.buildPingPacket(
                            isFindMe = false,
                            batteryPercent = phoneBattery()
                        )
                    )
                }
                delay(BleConstants.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(5_000)
                if (writeChar != null && lastRxAt > 0 &&
                    System.currentTimeMillis() - lastRxAt > BleConstants.RX_WATCHDOG_MS
                ) {
                    Log.w(TAG, "RX watchdog fired — disconnecting")
                    gatt?.disconnect()
                }
            }
        }
    }

    private suspend fun safeWrite(data: ByteArray): Boolean {
        val characteristic = writeChar ?: return false
        val gattLocal = gatt ?: return false
        return writeMutex.withLock {
            val ack = CompletableDeferred<Boolean>()
            writeAck = ack
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val started = gattLocal.writeCharacteristic(characteristic)
            if (!started) {
                writeAck = null
                return@withLock false
            }
            withTimeoutOrNull(BleConstants.WRITE_TIMEOUT_MS) { ack.await() } ?: false
        }
    }

    private fun phoneBattery(): Int {
        val bm = context.getSystemService(BatteryManager::class.java)
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }

    companion object {
        private const val TAG = "ScooterBleManager"

        @Volatile
        private var instance: ScooterBleManager? = null

        fun get(context: Context): ScooterBleManager =
            instance ?: synchronized(this) {
                instance ?: ScooterBleManager(context.applicationContext).also { instance = it }
            }
    }
}
