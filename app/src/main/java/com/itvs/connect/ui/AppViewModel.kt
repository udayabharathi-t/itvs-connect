package com.itvs.connect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itvs.connect.ble.ClusterStatsRotator
import com.itvs.connect.ble.ConnectionState
import com.itvs.connect.ble.MapsNavigationStore
import com.itvs.connect.ble.ScooterBleManager
import com.itvs.connect.ble.ScooterBleService
import com.itvs.connect.data.AppDatabase
import com.itvs.connect.data.AppSettings
import com.itvs.connect.data.PreferencesRepository
import com.itvs.connect.data.RideEntity
import com.itvs.connect.data.RideStatsCalculator
import com.itvs.connect.data.RideTracker
import com.itvs.connect.data.SavedPlaceEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUi(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val fuelPercent: Int = 0,
    val odometerKm: Double = 0.0,
    val afe: Int = 0,
    val dte: Int = 0,
    val serviceReminder: Int = 0,
    val isPinging: Boolean = false,
    val telemetryActive: Boolean = false,
    val activeRide: RideTracker.ActiveRideUi? = null,
    val settings: AppSettings = AppSettings(),
    val totalDistanceKm: Double = 0.0,
    val avgEconomy: Double? = null,
    val discovered: List<com.itvs.connect.ble.DiscoveredDevice> = emptyList(),
    val statusMessage: String = "",
    val clusterPageIndex: Int = 0,
    val clusterHudRunning: Boolean = false,
    val navigating: Boolean = false,
    val navApproachLock: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = PreferencesRepository(app)
    private val db = AppDatabase.get(app)
    private val ble = ScooterBleManager.get(app)

    // Ride tracker lives in the service; UI observes ble + prefs + db.
    // Active ride is best-effort via a lightweight local tracker mirror if service bound later.
    private var boundTracker: RideTracker? = null

    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val rides = db.rideDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val parked = db.parkedLocationDao().observeRecent(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val places = db.savedPlaceDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _activeRide = kotlinx.coroutines.flow.MutableStateFlow<RideTracker.ActiveRideUi?>(null)

    private val _clusterPageIndex = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val _clusterHudRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var boundService: ScooterBleService? = null
    private var activeRideCollectJob: Job? = null
    private var hudRefreshJob: Job? = null

    val dashboard: StateFlow<DashboardUi> = kotlinx.coroutines.flow.combine(
        listOf(
            ble.connectionState,
            ble.fuelLevel,
            ble.odometer,
            ble.averageFuelEconomy,
            ble.distanceToEmpty,
            ble.serviceReminder,
            ble.isPinging,
            ble.isTelemetryActive,
            _activeRide,
            prefs.settings,
            db.rideDao().observeTotalDistance(),
            db.rideDao().observeAverageEconomy(),
            ble.discoveredDevices,
            ble.statusMessage,
            _clusterPageIndex,
            _clusterHudRunning,
            MapsNavigationStore.updates
        )
    ) { values ->
        val maps = values[16] as com.itvs.connect.ble.MapsNavSnapshot
        DashboardUi(
            connection = values[0] as ConnectionState,
            fuelPercent = values[1] as Int,
            odometerKm = values[2] as Double,
            afe = values[3] as Int,
            dte = values[4] as Int,
            serviceReminder = values[5] as Int,
            isPinging = values[6] as Boolean,
            telemetryActive = values[7] as Boolean,
            activeRide = values[8] as RideTracker.ActiveRideUi?,
            settings = values[9] as AppSettings,
            totalDistanceKm = values[10] as Double,
            avgEconomy = values[11] as Double?,
            discovered = values[12] as List<com.itvs.connect.ble.DiscoveredDevice>,
            statusMessage = values[13] as String,
            clusterPageIndex = values[14] as Int,
            clusterHudRunning = values[15] as Boolean,
            navigating = maps.isNavigating,
            navApproachLock = maps.isApproachLock
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardUi())

    fun bindService(service: ScooterBleService?) {
        boundService = service
        boundTracker = service?.tracker()
        activeRideCollectJob?.cancel()
        hudRefreshJob?.cancel()
        if (service == null) {
            _activeRide.value = null
            _clusterHudRunning.value = false
            return
        }
        activeRideCollectJob = viewModelScope.launch {
            service.tracker().activeRide.collect { _activeRide.value = it }
        }
        hudRefreshJob = viewModelScope.launch {
            while (true) {
                refreshClusterHudState()
                delay(1_000)
            }
        }
        refreshClusterHudState()
    }

    fun bindTracker(tracker: RideTracker?) {
        boundTracker = tracker
        activeRideCollectJob?.cancel()
        activeRideCollectJob = viewModelScope.launch {
            tracker?.activeRide?.collect { _activeRide.value = it }
        }
    }

    fun refreshClusterHudState() {
        val svc = boundService ?: return
        _clusterHudRunning.value = svc.isStatsHudRunning()
        _clusterPageIndex.value = if (svc.isNavigatingHud()) {
            svc.currentNavPageIndex()
        } else {
            svc.currentStatsPageIndex()
        }
    }

    fun showClusterPage(index: Int) {
        ScooterBleService.startShowStatsPage(getApplication(), index)
        _clusterHudRunning.value = true
        val max = if (MapsNavigationStore.snapshot.isNavigating) {
            ClusterStatsRotator.NAV_PAGE_COUNT - 1
        } else {
            ClusterStatsRotator.PAGE_COUNT - 1
        }
        _clusterPageIndex.value = index.coerceIn(0, max)
        viewModelScope.launch {
            delay(150)
            refreshClusterHudState()
        }
    }

    fun stopClusterHud() {
        ScooterBleService.start(getApplication(), ScooterBleService.ACTION_STOP_STATS_HUD)
        _clusterHudRunning.value = false
    }

    fun startService() {
        ScooterBleService.start(getApplication(), ScooterBleService.ACTION_AUTO_RECONNECT)
    }

    fun scan() {
        ScooterBleService.start(getApplication(), ScooterBleService.ACTION_START_SCAN)
    }

    fun connectMac(mac: String) {
        val intent = android.content.Intent(getApplication(), ScooterBleService::class.java)
            .setAction(ScooterBleService.ACTION_CONNECT_MAC)
            .putExtra(ScooterBleService.EXTRA_MAC, mac)
        getApplication<Application>().startForegroundService(intent)
    }

    fun disconnect() {
        ScooterBleService.start(getApplication(), ScooterBleService.ACTION_DISCONNECT)
    }

    fun findMe() {
        ScooterBleService.start(getApplication(), ScooterBleService.ACTION_FIND_ME)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { prefs.updateSettings(transform) }
    }

    fun clearScooter() {
        viewModelScope.launch { prefs.clearScooter() }
        disconnect()
    }

    fun deleteRide(id: Long) {
        viewModelScope.launch { db.rideDao().delete(id) }
    }

    fun mergeRides(ids: List<Long>) {
        viewModelScope.launch {
            val rides = db.rideDao().getByIds(ids)
            if (rides.size < 2) return@launch
            val merged = RideStatsCalculator.mergeRides(rides)
            db.rideDao().insert(merged)
            db.rideDao().deleteIds(rides.map { it.id })
        }
    }

    fun updateRideLabel(id: Long, label: String) {
        viewModelScope.launch { db.rideDao().updateLabel(id, label.trim()) }
    }

    fun enrichRidePlaceNames(id: Long) {
        viewModelScope.launch {
            boundTracker?.enrichPlaceNames(id)
                ?: RideTracker(getApplication(), db, prefs).enrichPlaceNames(id)
        }
    }

    fun ride(id: Long): StateFlow<RideEntity?> =
        db.rideDao().observeById(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun savePlace(name: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            db.savedPlaceDao().insert(
                SavedPlaceEntity(name = name, latitude = lat, longitude = lng)
            )
        }
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch { db.savedPlaceDao().delete(id) }
    }

    fun dropPin() {
        viewModelScope.launch {
            boundTracker?.captureParkedLocation(isManual = true)
                ?: RideTracker(getApplication(), db, prefs).captureParkedLocation(true)
        }
    }
}
