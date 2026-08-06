package com.itvs.connect.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.itvs.connect.MainActivity
import com.itvs.connect.R
import com.itvs.connect.data.AppDatabase
import com.itvs.connect.data.AppSettings
import com.itvs.connect.data.PreferencesRepository
import com.itvs.connect.data.RideTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScooterBleService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ScooterBleService = this@ScooterBleService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var ble: ScooterBleManager
    private lateinit var prefs: PreferencesRepository
    private lateinit var rideTracker: RideTracker
    private lateinit var media: PhoneMediaController
    private lateinit var settings: AppSettings

    private var wakeLock: PowerManager.WakeLock? = null
    private var rideEndJob: Job? = null
    /** Independent of collectLatest — must not be cancelled by connection-state churn. */
    private var rideFinalizeJob: Job? = null
    private var flashClearJob: Job? = null
    private var autoReconnectJob: Job? = null
    private var callState: String = TelephonyManager.EXTRA_STATE_IDLE
    /** True after the user taps Disconnect — pause auto-reconnect until next app open / BT on. */
    private var userDisconnected = false
    private lateinit var statsRotator: ClusterStatsRotator

    private lateinit var gestureDetector: ButtonGestureDetector

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ble = ScooterBleManager.get(this)
        prefs = PreferencesRepository(this)
        val db = AppDatabase.get(this)
        rideTracker = RideTracker(this, db, prefs)
        media = PhoneMediaController(this)
        settings = AppSettings()

        gestureDetector = ButtonGestureDetector(
            scope = scope,
            onGesture = { count, isLong -> handleGesture(count, isLong) },
            onContinuous = { action -> media.execute(action) },
            continuousActionProvider = {
                when {
                    // During hold coalescing we approximate long action for count==1
                    else -> settings.longPress
                }
            }
        )

        statsRotator = ClusterStatsRotator(
            scope = scope,
            flash = { r1, r2 -> flashCluster(r1, r2) },
            provider = {
                ClusterStatsRotator.fromLive(
                    ride = rideTracker.activeRide.value,
                    liveKmL = ble.freshLiveEconomyKmL(),
                    maps = MapsNavigationStore.snapshot,
                    fuelCostPerLitre = settings.fuelCostPerLitre
                )
            }
        )

        startForeground(NOTIF_ID, buildNotification("Starting…"))
        observeStreams()
        registerReceiver(callReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        scope.launch {
            settings = prefs.settings.first()
            ble.riderName = settings.riderName
            maybeStartAutoReconnect(reason = "service-start")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FIND_ME -> ble.findMe()
            ACTION_START_SCAN -> scope.launch {
                userDisconnected = false
                val s = prefs.settings.first()
                settings = s
                // Manual scan: if we already know the MAC, reconnect that scooter.
                if (s.scooterMac.isNotBlank()) {
                    ble.reconnectSaved(s.scooterMac)
                } else {
                    ble.startScan(null)
                }
            }
            ACTION_AUTO_RECONNECT -> scope.launch {
                settings = prefs.settings.first()
                ble.riderName = settings.riderName
                maybeStartAutoReconnect(reason = "explicit")
            }
            ACTION_CONNECT_MAC -> {
                userDisconnected = false
                val mac = intent.getStringExtra(EXTRA_MAC).orEmpty()
                if (mac.isNotBlank()) ble.connectMac(mac, autoConnect = false)
            }
            ACTION_DISCONNECT -> {
                userDisconnected = true
                autoReconnectJob?.cancel()
                autoReconnectJob = null
                // Persist the ride before tearing down GATT — state collectors can race.
                finalizeActiveRide(notifySaved = true)
                ble.disconnect()
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_CLUSTER_MESSAGE -> {
                // Don't clobber an active stats rotation with unrelated mirrors.
                if (!statsRotator.isRunning) {
                    val r1 = intent.getStringExtra(EXTRA_ROW1).orEmpty()
                    val r2 = intent.getStringExtra(EXTRA_ROW2).orEmpty()
                    if (r1.isNotBlank()) flashCluster(r1, r2)
                }
            }
            ACTION_SHOW_STATS_PAGE -> {
                val page = intent.getIntExtra(EXTRA_PAGE, 0)
                val label = statsRotator.showPage(page)
                notify("Stats · $label")
            }
            ACTION_STOP_STATS_HUD -> {
                statsRotator.stop()
                notify(getString(R.string.service_notification_title))
            }
            null -> scope.launch {
                settings = prefs.settings.first()
                maybeStartAutoReconnect(reason = "sticky-restart")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { unregisterReceiver(callReceiver) }
        runCatching { unregisterReceiver(bluetoothReceiver) }
        autoReconnectJob?.cancel()
        statsRotator.stop()
        rideEndJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    fun manager(): ScooterBleManager = ble
    fun tracker(): RideTracker = rideTracker
    fun isStatsHudRunning(): Boolean = statsRotator.isRunning
    fun currentStatsPageIndex(): Int = statsRotator.currentIndex
    fun currentNavPageIndex(): Int = statsRotator.currentNavIndex
    fun isNavigatingHud(): Boolean = MapsNavigationStore.snapshot.isNavigating

    fun showStatsPage(pageIndex: Int): String {
        val label = statsRotator.showPage(pageIndex)
        notify("Stats · $label")
        return label
    }

    fun stopStatsHud() {
        statsRotator.stop()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun maybeStartAutoReconnect(reason: String) {
        if (userDisconnected) return
        if (!settings.autoConnect) return
        if (settings.scooterMac.isBlank()) return
        when (ble.connectionState.value) {
            is ConnectionState.Connected,
            ConnectionState.Connecting,
            ConnectionState.Authenticating,
            ConnectionState.Scanning -> return
            else -> Unit
        }
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            var attempt = 0
            while (isActive && settings.autoConnect && !userDisconnected) {
                val mac = settings.scooterMac
                if (mac.isBlank()) break
                when (ble.connectionState.value) {
                    is ConnectionState.Connected -> break
                    ConnectionState.Connecting,
                    ConnectionState.Authenticating,
                    ConnectionState.Scanning -> {
                        delay(4_000)
                        continue
                    }
                    else -> Unit
                }
                attempt++
                notify("Auto-connecting… (#$attempt)")
                ble.reconnectSaved(mac)
                // Wait for connect attempt to settle before retrying.
                delay(if (attempt <= 2) 18_000L else 30_000L)
            }
        }
    }

    private fun observeStreams() {
        scope.launch {
            prefs.settings.collectLatest {
                val wasAuto = settings.autoConnect
                settings = it
                ble.riderName = it.riderName
                gestureDetector.updateTiming(
                    it.doublePressWindowMs,
                    it.longPressThresholdMs,
                    it.cooldownMs
                )
                if (it.autoConnect && (!wasAuto || it.scooterMac.isNotBlank())) {
                    userDisconnected = false
                    maybeStartAutoReconnect(reason = "settings")
                }
                if (!it.autoConnect) {
                    autoReconnectJob?.cancel()
                    autoReconnectJob = null
                }
            }
        }
        scope.launch {
            ble.connectionState.collectLatest { state ->
                val text = when (state) {
                    ConnectionState.Disconnected -> "Disconnected"
                    ConnectionState.Scanning -> getString(R.string.scanning_notification_title)
                    ConnectionState.Connecting -> "Connecting…"
                    ConnectionState.Authenticating -> "Authenticating…"
                    is ConnectionState.Connected -> {
                        prefs.setScooter(state.mac, state.deviceName)
                        getString(R.string.service_notification_title)
                    }
                    is ConnectionState.Failed -> state.reason.take(80).ifBlank { "Pairing issue" }
                }
                notify(text)

                when (state) {
                    is ConnectionState.Connected -> {
                        userDisconnected = false
                        autoReconnectJob?.cancel()
                        autoReconnectJob = null
                    }
                    ConnectionState.Disconnected,
                    is ConnectionState.Failed -> {
                        statsRotator.stop()
                        // Launch on service scope so collectLatest re-entries cannot cancel the save.
                        val saveJob = finalizeActiveRide(notifySaved = true)
                        // Unexpected drop — keep trying if auto-connect is on.
                        // Wait for ride persistence so a reconnect cannot race the snapshot.
                        if (!userDisconnected && settings.autoConnect && settings.scooterMac.isNotBlank()) {
                            scope.launch {
                                saveJob?.join()
                                maybeStartAutoReconnect(reason = "disconnect")
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            ble.isTelemetryActive.collectLatest { active ->
                if (active) {
                    rideEndJob?.cancel()
                    rideEndJob = null
                    acquireWakeLock()
                    val s = settings
                    rideTracker.startRideIfNeeded(
                        odometerKm = ble.odometer.value,
                        fuelPercent = ble.fuelLevel.value,
                        liveKmL = ble.freshLiveEconomyKmL() ?: 0,
                        tankCapacityLitres = s.tankCapacityLitres
                    )
                    notify(getString(R.string.ride_notification_title))
                    // Auto-start continuous stats HUD when a gesture is mapped to it.
                    if (isRideStatsMapped() && !statsRotator.isRunning) {
                        statsRotator.start(resetIndex = true)
                    }
                } else if (rideTracker.isActive()) {
                    // Soft end: grace period in case of brief telemetry gaps while still connected.
                    // Hard disconnect path finalizes immediately via finalizeActiveRide().
                    if (ble.connectionState.value is ConnectionState.Connected) {
                        rideEndJob?.cancel()
                        rideEndJob = scope.launch {
                            delay(BleConstants.RIDE_END_GRACE_MS)
                            if (!ble.isTelemetryActive.value &&
                                ble.connectionState.value is ConnectionState.Connected &&
                                rideTracker.isActive()
                            ) {
                                val saved = rideTracker.endRideIfNeeded()
                                releaseWakeLock()
                                statsRotator.stop()
                                notify(
                                    if (saved != null) {
                                        "Ride saved"
                                    } else {
                                        getString(R.string.service_notification_title)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            ble.liveEconomySamples.collectLatest { liveKmL ->
                rideTracker.onLiveEconomy(liveKmL)
            }
        }
        scope.launch {
            ble.telemetryPersisted.collectLatest {
                prefs.persistTelemetry(
                    fuel = ble.fuelLevel.value,
                    odo = ble.odometer.value,
                    afe = ble.averageFuelEconomy.value.takeIf { it in 1..99 }
                        ?: ble.liveFuelEconomy.value,
                    dte = ble.distanceToEmpty.value
                )
                if (rideTracker.isActive()) {
                    rideTracker.onTelemetry(
                        ble.odometer.value,
                        ble.fuelLevel.value,
                        ble.freshLiveEconomyKmL() ?: 0,
                        settings.tankCapacityLitres
                    )
                }
            }
        }
        scope.launch {
            MapsNavigationStore.updates.collectLatest { snap ->
                if (!snap.isNavigating) return@collectLatest
                // Navigation HUD: keep cluster fed even if Voice stats gesture isn't mapped.
                if (ble.connectionState.value is ConnectionState.Connected &&
                    !statsRotator.isRunning
                ) {
                    statsRotator.start(resetIndex = true)
                    notify("Nav HUD · active")
                }
            }
        }
        scope.launch {
            ble.buttonHold.collectLatest {
                if (settings.requireHeadphones && !media.headphonesConnected()) return@collectLatest
                gestureDetector.onHold()
            }
        }
        scope.launch {
            ble.buttonRelease.collectLatest {
                gestureDetector.onRelease()
            }
        }
        scope.launch {
            ble.musicCommands.collectLatest { media.executeMusicCommand(it) }
        }
        scope.launch {
            ble.callCommands.collectLatest { cmd ->
                when (cmd) {
                    1 -> media.answerCall()
                    2, 3 -> media.declineOrEndCall()
                }
            }
        }
        scope.launch {
            var lastTrack: String? = null
            while (true) {
                delay(3_000)
                if (!settings.appNotificationsEnabled) continue
                if (!rideTracker.isActive()) continue
                if (statsRotator.isRunning) continue
                val label = media.currentTrackLabel() ?: continue
                if (label == lastTrack) continue
                lastTrack = label
                flashCluster("Now Playing", label)
            }
        }
    }

    private fun handleGesture(count: Int, isLong: Boolean) {
        // Call overrides
        if (callState == TelephonyManager.EXTRA_STATE_RINGING ||
            callState == TelephonyManager.EXTRA_STATE_OFFHOOK
        ) {
            val answer = matchesCallGesture(settings.callAnswerGesture, count, isLong)
            val decline = matchesCallGesture(settings.callDeclineGesture, count, isLong)
            when {
                answer && callState == TelephonyManager.EXTRA_STATE_RINGING -> {
                    media.answerCall(); return
                }
                decline -> {
                    media.declineOrEndCall(); return
                }
            }
        }

        val action = when {
            !isLong && count == 1 -> settings.singlePress
            !isLong && count == 2 -> settings.doublePress
            !isLong && count >= 3 -> settings.triplePress
            isLong && count == 1 -> settings.longPress
            isLong && count == 2 -> settings.singleLongPress
            isLong && count == 3 -> settings.doubleLongPress
            isLong && count >= 4 -> settings.tripleLongPress
            else -> ButtonAction.DO_NOTHING
        }
        if (action == ButtonAction.ROTATE_RIDE_STATS) {
            // Each press advances the page; continuous refresh keeps Assist ready away.
            val label = statsRotator.nextPage()
            // Brief confirmation is unnecessary — nextPage already flashes the new stat.
            notify("Stats · $label")
            return
        }
        val dial = when {
            !isLong && count == 1 -> settings.speedDialSingle
            !isLong && count == 2 -> settings.speedDialDouble
            !isLong && count >= 3 -> settings.speedDialTriple
            else -> settings.speedDialLong
        }
        media.execute(action, dial)
        if (action != ButtonAction.DO_NOTHING && action.displayName.isNotBlank()) {
            flashCluster(action.displayName.take(17))
        }
    }

    private fun isRideStatsMapped(): Boolean {
        return settings.singlePress == ButtonAction.ROTATE_RIDE_STATS ||
            settings.doublePress == ButtonAction.ROTATE_RIDE_STATS ||
            settings.triplePress == ButtonAction.ROTATE_RIDE_STATS ||
            settings.longPress == ButtonAction.ROTATE_RIDE_STATS ||
            settings.singleLongPress == ButtonAction.ROTATE_RIDE_STATS ||
            settings.doubleLongPress == ButtonAction.ROTATE_RIDE_STATS ||
            settings.tripleLongPress == ButtonAction.ROTATE_RIDE_STATS
    }

    private fun matchesCallGesture(gesture: CallGesture, count: Int, isLong: Boolean): Boolean {
        return when (gesture) {
            CallGesture.DISABLE -> false
            CallGesture.SINGLE_PRESS -> !isLong && count == 1
            CallGesture.DOUBLE_PRESS -> !isLong && count == 2
            CallGesture.TRIPLE_PRESS -> !isLong && count >= 3
            CallGesture.LONG_PRESS -> isLong && count == 1
        }
    }

    private fun flashCluster(row1: String, row2: String = "") {
        flashClearJob?.cancel()
        ble.sendClusterMessage(row1, row2)
        flashClearJob = scope.launch {
            delay(BleConstants.CLUSTER_FLASH_MS)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) {
                userDisconnected = false
                scope.launch {
                    settings = prefs.settings.first()
                    maybeStartAutoReconnect(reason = "bluetooth-on")
                }
            }
        }
    }

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            callState = state
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    ble.sendCallUpdate(number ?: "Unknown", incoming = true)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // active call — keep last flash
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // clear handled by next heartbeat/UI
                }
            }
        }
    }

    /**
     * Persist an in-progress ride. Coalesces concurrent callers and runs on the
     * service scope so [kotlinx.coroutines.flow.collectLatest] cancellation cannot
     * abort the database write.
     */
    private fun finalizeActiveRide(notifySaved: Boolean): Job? {
        rideEndJob?.cancel()
        rideEndJob = null
        rideFinalizeJob?.takeIf { it.isActive }?.let { return it }
        if (!rideTracker.isActive()) return null
        rideFinalizeJob = scope.launch {
            val saved = rideTracker.endRideIfNeeded()
            releaseWakeLock()
            statsRotator.stop()
            if (notifySaved && saved != null) {
                notify("Ride saved · disconnected")
            }
        }
        return rideFinalizeJob
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "itvs:ride").also {
            it.setReferenceCounted(false)
            it.acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONN,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RIDE,
                getString(R.string.ride_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val findMe = PendingIntent.getService(
            this,
            1,
            Intent(this, ScooterBleService::class.java).setAction(ACTION_FIND_ME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_CONN)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .addAction(0, "Find Me", findMe)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_FIND_ME = "com.itvs.connect.action.FIND_ME"
        const val ACTION_START_SCAN = "com.itvs.connect.action.START_SCAN"
        const val ACTION_AUTO_RECONNECT = "com.itvs.connect.action.AUTO_RECONNECT"
        const val ACTION_CONNECT_MAC = "com.itvs.connect.action.CONNECT_MAC"
        const val ACTION_DISCONNECT = "com.itvs.connect.action.DISCONNECT"
        const val ACTION_STOP = "com.itvs.connect.action.STOP"
        const val ACTION_CLUSTER_MESSAGE = "com.itvs.connect.action.CLUSTER_MESSAGE"
        const val ACTION_SHOW_STATS_PAGE = "com.itvs.connect.action.SHOW_STATS_PAGE"
        const val ACTION_STOP_STATS_HUD = "com.itvs.connect.action.STOP_STATS_HUD"
        const val EXTRA_ROW1 = "row1"
        const val EXTRA_ROW2 = "row2"
        const val EXTRA_MAC = "mac"
        const val EXTRA_PAGE = "page"

        private const val CHANNEL_CONN = "scooter_conn"
        private const val CHANNEL_RIDE = "scooter_ride"
        private const val NOTIF_ID = 42

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, ScooterBleService::class.java)
            if (action != null) intent.action = action
            context.startForegroundService(intent)
        }

        fun startShowStatsPage(context: Context, pageIndex: Int) {
            val intent = Intent(context, ScooterBleService::class.java)
                .setAction(ACTION_SHOW_STATS_PAGE)
                .putExtra(EXTRA_PAGE, pageIndex)
            context.startForegroundService(intent)
        }
    }
}
