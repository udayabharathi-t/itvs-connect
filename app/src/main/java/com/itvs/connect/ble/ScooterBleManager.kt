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
    private var scanTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var expectingPairing = false
    private var connectAttemptMac: String? = null
    /** True once SmartXonnect GATT chars are ready — never flip UI back to Authenticating. */
    private var gattLinkReady = false

    private var lastRxAt = 0L
    private var lastPacketAt = 0L
    private var consecutive10 = 0
    private var buttonHeld = false
    private var buttonDownTime = 0L
    private var hasSeenNormalCycle = false
    private var lastTelemetryPersistAt = 0L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

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

    /** Best live km/L from latest economy packet (IFE preferred, else AFE). 0 = none. */
    private val _liveFuelEconomy = MutableStateFlow(0)
    val liveFuelEconomy: StateFlow<Int> = _liveFuelEconomy.asStateFlow()

    /** Epoch ms of last economy packet that carried a valid live km/L. */
    private val _liveEconomyUpdatedAtMs = MutableStateFlow(0L)
    val liveEconomyUpdatedAtMs: StateFlow<Long> = _liveEconomyUpdatedAtMs.asStateFlow()

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

    /** Fires on every economy packet with a valid live km/L (not throttled). */
    private val _liveEconomySamples = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    val liveEconomySamples: SharedFlow<Int> = _liveEconomySamples.asSharedFlow()

    fun freshLiveEconomyKmL(nowMs: Long = System.currentTimeMillis()): Int? {
        val value = _liveFuelEconomy.value
        val at = _liveEconomyUpdatedAtMs.value
        if (!TelemetryParser.isValidKmL(value) || at <= 0L) return null
        if (nowMs - at > LIVE_ECONOMY_STALE_MS) return null
        return value
    }

    var riderName: String = "iTVS"

    /**
     * Reconnect to a previously saved scooter MAC.
     * 1) Short MAC-filtered scan + active connect when seen
     * 2) If not advertising yet, open a passive GATT autoConnect wait
     */
    fun reconnectSaved(mac: String) {
        if (mac.isBlank()) return
        when (_connectionState.value) {
            is ConnectionState.Connected,
            ConnectionState.Connecting,
            ConnectionState.Authenticating,
            ConnectionState.Scanning -> return
            else -> Unit
        }
        val normalized = mac.uppercase()
        _statusMessage.value = "Auto-connecting to saved scooter…"
        // Prefer finding it via scan (faster when cluster is already on).
        startScan(preferredMac = normalized, reconnectMode = true)
    }

    fun startScan(preferredMac: String? = null, reconnectMode: Boolean = false) {
        val bluetoothAdapter = adapter ?: run {
            fail("Bluetooth adapter unavailable")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            fail("Turn on Bluetooth and retry")
            return
        }

        stopScan()
        expectingPairing = !reconnectMode && preferredMac.isNullOrBlank()
        if (!reconnectMode) {
            _discoveredDevices.value = emptyList()
        }
        _connectionState.value = ConnectionState.Scanning
        _statusMessage.value = if (reconnectMode) {
            "Looking for saved scooter…"
        } else {
            "Scanning nearby BLE devices… Keep TVS Connect force-stopped and scooter cluster on."
        }

        // Seed list from already-bonded devices (often includes the scooter MAC).
        runCatching {
            bluetoothAdapter.bondedDevices.orEmpty().forEach { device ->
                rememberDiscovered(device, rssi = -50, fromBond = true)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        // IMPORTANT: do NOT filter by SERVICE_UUID in advertisements.
        // TVS SmartXonnect clusters usually do not advertise that UUID, so a UUID
        // filter makes pairing hang forever. Filter by MAC only when reconnecting.
        val filters = if (!preferredMac.isNullOrBlank()) {
            listOf(ScanFilter.Builder().setDeviceAddress(preferredMac.uppercase()).build())
        } else {
            emptyList()
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                rememberDiscovered(device, result.rssi, fromBond = false)

                if (!preferredMac.isNullOrBlank()) {
                    if (device.address.equals(preferredMac, ignoreCase = true)) {
                        stopScan()
                        connect(device, autoConnect = false, passiveWait = false)
                    }
                    return
                }

                // First-time pairing: auto-connect only to strongly likely scooters.
                if (isLikelyScooterName(device.name) && gatt == null &&
                    _connectionState.value is ConnectionState.Scanning
                ) {
                    _statusMessage.value = "Found ${device.name ?: "scooter"} — connecting…"
                    stopScan()
                    connect(device, autoConnect = false, passiveWait = false)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                if (reconnectMode && !preferredMac.isNullOrBlank()) {
                    // Fall back to passive GATT wait.
                    connectMac(preferredMac, autoConnect = true, passiveWait = true)
                    return
                }
                fail("BLE scan failed (code $errorCode). Toggle Bluetooth and retry.")
            }
        }
        scanCallback = callback
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            fail("BLE scanner unavailable. Toggle Bluetooth and retry.")
            return
        }
        if (filters.isEmpty()) {
            scanner.startScan(null, settings, callback)
        } else {
            scanner.startScan(filters, settings, callback)
        }

        scanTimeoutJob?.cancel()
        val timeout = if (reconnectMode) RECONNECT_SCAN_TIMEOUT_MS else SCAN_TIMEOUT_MS
        scanTimeoutJob = scope.launch {
            delay(timeout)
            if (_connectionState.value is ConnectionState.Scanning) {
                stopScan()
                if (reconnectMode && !preferredMac.isNullOrBlank()) {
                    _statusMessage.value = "Scooter not advertising yet — waiting for it…"
                    connectMac(preferredMac, autoConnect = true, passiveWait = true)
                    return@launch
                }
                val count = _discoveredDevices.value.size
                val likely = _discoveredDevices.value.count { it.likelyScooter }
                fail(
                    if (count == 0) {
                        "No BLE devices found. Turn scooter ON, stand near it, enable Location, and force-stop TVS Connect."
                    } else {
                        "Scan timed out. Tap a device below to connect" +
                            if (likely > 0) " (preferred: $likely likely scooter(s))."
                            else " (no TVS-like name seen — try unnamed/strongest nearby device)."
                    }
                )
                // Keep discovered list visible for manual tap.
                _connectionState.value = ConnectionState.Failed(_statusMessage.value)
            }
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        scanCallback?.let {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(it) }
        }
        scanCallback = null
    }

    fun connect(device: BluetoothDevice, autoConnect: Boolean = false, passiveWait: Boolean = false) {
        stopScan()
        connectTimeoutJob?.cancel()
        gattLinkReady = false
        connectAttemptMac = device.address
        _connectionState.value = ConnectionState.Connecting
        _statusMessage.value = if (passiveWait) {
            "Waiting for ${device.name ?: "saved scooter"}…"
        } else {
            "Connecting to ${device.name ?: device.address}…"
        }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        readChar = null
        gatt = device.connectGatt(context, autoConnect || passiveWait, gattCallback, BluetoothDevice.TRANSPORT_LE)
        // Don't leave the UI stuck on Connecting / Authenticating if discovery hangs.
        val timeout = if (passiveWait) PASSIVE_CONNECT_TIMEOUT_MS else CONNECT_READY_TIMEOUT_MS
        connectTimeoutJob = scope.launch {
            delay(timeout)
            val state = _connectionState.value
            if (state is ConnectionState.Connecting || state is ConnectionState.Authenticating) {
                if (gattLinkReady || (writeChar != null && readChar != null)) {
                    markConnected(gatt?.device)
                    _statusMessage.value = "Connected — syncing with cluster…"
                } else if (passiveWait) {
                    // Soft fail — leave Disconnected so the service can retry auto-reconnect.
                    runCatching { gatt?.disconnect() }
                    runCatching { gatt?.close() }
                    gatt = null
                    _statusMessage.value = "Still waiting for scooter — will retry"
                    _connectionState.value = ConnectionState.Disconnected
                } else {
                    fail("Connection timed out. Retry scan / force-stop TVS Connect, then try again.")
                    runCatching { gatt?.disconnect() }
                }
            }
        }
    }

    fun connectMac(mac: String, autoConnect: Boolean = false, passiveWait: Boolean = false) {
        val device = adapter?.getRemoteDevice(mac) ?: run {
            fail("Invalid MAC address")
            return
        }
        connect(device, autoConnect = autoConnect, passiveWait = passiveWait)
    }

    fun disconnect() {
        expectingPairing = false
        stopScan()
        heartbeatJob?.cancel()
        findMeJob?.cancel()
        watchdogJob?.cancel()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        gattLinkReady = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        readChar = null
        connectAttemptMac = null
        hasSeenNormalCycle = false
        _isTelemetryActive.value = false
        _statusMessage.value = ""
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun rememberDiscovered(device: BluetoothDevice, rssi: Int, fromBond: Boolean) {
        val name = device.name?.takeIf { it.isNotBlank() }
            ?: if (fromBond) "Bonded device" else "Unknown"
        val mac = device.address ?: return
        val likely = isLikelyScooterName(device.name)
        val current = _discoveredDevices.value.toMutableList()
        val idx = current.indexOfFirst { it.mac.equals(mac, ignoreCase = true) }
        val entry = DiscoveredDevice(name = name, mac = mac, rssi = rssi, likelyScooter = likely)
        if (idx >= 0) {
            // Keep the stronger RSSI / better name.
            val old = current[idx]
            current[idx] = entry.copy(
                name = if (name != "Unknown" && name != "Bonded device") name else old.name,
                rssi = maxOf(old.rssi, rssi),
                likelyScooter = old.likelyScooter || likely
            )
        } else {
            current += entry
        }
        _discoveredDevices.value = current
            .sortedWith(
                compareByDescending<DiscoveredDevice> { it.likelyScooter }
                    .thenByDescending { it.rssi }
            )
            .take(30)
    }

    private fun fail(reason: String) {
        stopScan()
        _statusMessage.value = reason
        _connectionState.value = ConnectionState.Failed(reason)
        Log.w(TAG, reason)
    }

    private fun isLikelyScooterName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return SCOOTER_NAME_HINTS.any { n.contains(it) }
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
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Connected with bad status=$status")
                }
                _connectionState.value = ConnectionState.Authenticating
                _statusMessage.value = "Connected — discovering SmartXonnect service…"
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                heartbeatJob?.cancel()
                findMeJob?.cancel()
                watchdogJob?.cancel()
                connectTimeoutJob?.cancel()
                gattLinkReady = false
                hasSeenNormalCycle = false
                _isTelemetryActive.value = false
                writeChar = null
                readChar = null
                runCatching { gatt.close() }
                if (this@ScooterBleManager.gatt === gatt) {
                    this@ScooterBleManager.gatt = null
                }
                if (expectingPairing &&
                    _connectionState.value !is ConnectionState.Connected &&
                    _connectionState.value !is ConnectionState.Failed
                ) {
                    _statusMessage.value =
                        "Disconnected (status=$status). Tap a discovered device or scan again."
                    _connectionState.value = ConnectionState.Failed(_statusMessage.value)
                } else if (_connectionState.value !is ConnectionState.Failed) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed ($status). Retry scan.")
                gatt.disconnect()
                return
            }
            val service = gatt.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "No TVS service on ${gatt.device?.address} — wrong device")
                _statusMessage.value =
                    "Not a SmartXonnect cluster (${gatt.device?.name ?: gatt.device?.address}). Pick another device."
                // Keep scanning list; disconnect this wrong peripheral.
                expectingPairing = true
                gatt.disconnect()
                _connectionState.value = ConnectionState.Failed(_statusMessage.value)
                return
            }
            writeChar = service.getCharacteristic(BleConstants.WRITE_UUID)
            readChar = service.getCharacteristic(BleConstants.READ_UUID)
            val notifyChar = readChar
            if (writeChar == null || notifyChar == null) {
                fail("Scooter service found but characteristics missing.")
                gatt.disconnect()
                return
            }
            gatt.setCharacteristicNotification(notifyChar, true)
            scope.launch {
                delay(BleConstants.CCCD_DELAY_MS)
                val cccd = notifyChar.getDescriptor(BleConstants.CCCD_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
                // Mark Connected as soon as the SmartXonnect GATT link is ready.
                // Auth/challenge may arrive later (or never on some units); don't leave UI stuck.
                markConnected(gatt.device)
                _statusMessage.value = "Connected — syncing with cluster…"
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

        // Also promote to Connected on first valid telemetry packet.
        val snapshot = TelemetryParser.parse(data) ?: return
        if (_connectionState.value is ConnectionState.Authenticating ||
            _connectionState.value is ConnectionState.Connecting
        ) {
            markConnected(gatt?.device)
        }
        if (snapshot.isIgnitionTelemetry) {
            hasSeenNormalCycle = true
            _isTelemetryActive.value = true
        }
        snapshot.odometerKm?.let { _odometer.value = it }
        snapshot.fuelPercent?.let { _fuelLevel.value = it }
        snapshot.averageFuelEconomy?.let { _averageFuelEconomy.value = it }
        // Economy packet with no valid live (IFE/-- at low speed and empty AFE):
        // clear live so HUD shows N/A instead of a sticky leftover.
        if (dataId == BleConstants.DATA_ID_ECONOMY) {
            val live = snapshot.liveFuelEconomy
            if (live != null) {
                _liveFuelEconomy.value = live
                _liveEconomyUpdatedAtMs.value = now
                _liveEconomySamples.tryEmit(live)
                Log.d(
                    TAG,
                    "Economy live=$live ife=${snapshot.instantFuelEconomy} afe=${snapshot.averageFuelEconomy}"
                )
            } else {
                _liveFuelEconomy.value = 0
                _liveEconomyUpdatedAtMs.value = 0L
            }
        }
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
        // Never regress UI to Authenticating once the GATT link is usable.
        if (!gattLinkReady && _connectionState.value !is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Authenticating
        }
        _statusMessage.value =
            if (gattLinkReady || _connectionState.value is ConnectionState.Connected) {
                "Syncing with cluster…"
            } else {
                "Authenticating with scooter…"
            }
        val ok = safeWrite(PacketBuilder.buildAuthResponsePacket(challenge))
        if (!ok) {
            // Stay connected if GATT is still up; auth can retry on next challenge.
            if (gattLinkReady) markConnected(gatt?.device)
            _statusMessage.value = "Auth write failed — still connected, retrying via heartbeat"
            return
        }
        delay(BleConstants.POST_AUTH_DELAY_MS)
        safeWrite(PacketBuilder.buildUserIdPacket())
        delay(BleConstants.POST_AUTH_DELAY_MS)
        safeWrite(PacketBuilder.buildRiderNamePacket(riderName))
        delay(BleConstants.POST_AUTH_DELAY_MS)
        markConnected(gatt?.device)
        _statusMessage.value = "Paired"
        startHeartbeat()
    }

    private fun markConnected(device: BluetoothDevice?) {
        expectingPairing = false
        gattLinkReady = true
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        _connectionState.value = ConnectionState.Connected(
            deviceName = device?.name ?: "TVS Scooter",
            mac = device?.address.orEmpty()
        )
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
        private const val SCAN_TIMEOUT_MS = 25_000L
        private const val RECONNECT_SCAN_TIMEOUT_MS = 12_000L
        /** Max time to sit on Connecting/Authenticating before promoting or failing. */
        private const val CONNECT_READY_TIMEOUT_MS = 12_000L
        /** Passive GATT autoConnect can wait longer for the cluster to wake. */
        private const val PASSIVE_CONNECT_TIMEOUT_MS = 45_000L
        /** Live km/L older than this is treated as missing (N/A). */
        const val LIVE_ECONOMY_STALE_MS = 15_000L
        private val SCOOTER_NAME_HINTS = listOf(
            "tvs", "jupiter", "ntorq", "ronin", "apache", "iqube",
            "smartx", "xonnect", "radeon", "sport", "rr310"
        )

        @Volatile
        private var instance: ScooterBleManager? = null

        fun get(context: Context): ScooterBleManager =
            instance ?: synchronized(this) {
                instance ?: ScooterBleManager(context.applicationContext).also { instance = it }
            }
    }
}
