package com.itvs.connect.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
    private var flashClearJob: Job? = null
    private var callState: String = TelephonyManager.EXTRA_STATE_IDLE
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
                    liveAfe = ble.averageFuelEconomy.value,
                    maps = MapsNavigationStore.snapshot
                )
            }
        )

        startForeground(NOTIF_ID, buildNotification("Starting…"))
        observeStreams()
        registerReceiver(callReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))

        scope.launch {
            settings = prefs.settings.first()
            ble.riderName = settings.riderName
            if (settings.autoConnect && settings.scooterMac.isNotBlank()) {
                ble.startScan(settings.scooterMac)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FIND_ME -> ble.findMe()
            ACTION_START_SCAN -> scope.launch {
                val s = prefs.settings.first()
                ble.startScan(s.scooterMac.ifBlank { null })
            }
            ACTION_CONNECT_MAC -> {
                val mac = intent.getStringExtra(EXTRA_MAC).orEmpty()
                if (mac.isNotBlank()) ble.connectMac(mac, autoConnect = false)
            }
            ACTION_DISCONNECT -> ble.disconnect()
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
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { unregisterReceiver(callReceiver) }
        statsRotator.stop()
        rideEndJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    fun manager(): ScooterBleManager = ble
    fun tracker(): RideTracker = rideTracker

    private fun observeStreams() {
        scope.launch {
            prefs.settings.collectLatest {
                settings = it
                ble.riderName = it.riderName
                gestureDetector.updateTiming(
                    it.doublePressWindowMs,
                    it.longPressThresholdMs,
                    it.cooldownMs
                )
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

                // Disconnect should immediately finalize the in-progress ride.
                if (state is ConnectionState.Disconnected || state is ConnectionState.Failed) {
                    statsRotator.stop()
                    if (rideTracker.isActive()) {
                        rideEndJob?.cancel()
                        rideEndJob = null
                        rideTracker.endRideIfNeeded()
                        releaseWakeLock()
                        notify("Ride saved · disconnected")
                    }
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
                        afe = ble.averageFuelEconomy.value,
                        tankCapacityLitres = s.tankCapacityLitres
                    )
                    notify(getString(R.string.ride_notification_title))
                } else if (rideTracker.isActive()) {
                    // Soft end: grace period in case of brief telemetry gaps while still connected.
                    // Hard disconnect path above ends immediately.
                    if (ble.connectionState.value is ConnectionState.Connected) {
                        rideEndJob?.cancel()
                        rideEndJob = scope.launch {
                            delay(BleConstants.RIDE_END_GRACE_MS)
                            if (!ble.isTelemetryActive.value && rideTracker.isActive()) {
                                rideTracker.endRideIfNeeded()
                                releaseWakeLock()
                                notify(getString(R.string.service_notification_title))
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            ble.telemetryPersisted.collectLatest {
                prefs.persistTelemetry(
                    fuel = ble.fuelLevel.value,
                    odo = ble.odometer.value,
                    afe = ble.averageFuelEconomy.value,
                    dte = ble.distanceToEmpty.value
                )
                if (rideTracker.isActive()) {
                    rideTracker.onTelemetry(
                        ble.odometer.value,
                        ble.fuelLevel.value,
                        ble.averageFuelEconomy.value,
                        settings.tankCapacityLitres
                    )
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
            if (statsRotator.isRunning) {
                statsRotator.stop()
                flashCluster("Stats stopped")
            } else {
                // Starts immediately with Ride time, then advances every 10s.
                statsRotator.start()
            }
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
        const val ACTION_CONNECT_MAC = "com.itvs.connect.action.CONNECT_MAC"
        const val ACTION_DISCONNECT = "com.itvs.connect.action.DISCONNECT"
        const val ACTION_STOP = "com.itvs.connect.action.STOP"
        const val ACTION_CLUSTER_MESSAGE = "com.itvs.connect.action.CLUSTER_MESSAGE"
        const val EXTRA_ROW1 = "row1"
        const val EXTRA_ROW2 = "row2"
        const val EXTRA_MAC = "mac"

        private const val CHANNEL_CONN = "scooter_conn"
        private const val CHANNEL_RIDE = "scooter_ride"
        private const val NOTIF_ID = 42

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, ScooterBleService::class.java)
            if (action != null) intent.action = action
            context.startForegroundService(intent)
        }
    }
}
