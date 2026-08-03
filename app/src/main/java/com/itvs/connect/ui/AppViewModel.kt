package com.itvs.connect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itvs.connect.ble.ConnectionState
import com.itvs.connect.ble.ScooterBleManager
import com.itvs.connect.ble.ScooterBleService
import com.itvs.connect.data.AppDatabase
import com.itvs.connect.data.AppSettings
import com.itvs.connect.data.PreferencesRepository
import com.itvs.connect.data.RideEntity
import com.itvs.connect.data.RideTracker
import com.itvs.connect.data.SavedPlaceEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val avgEconomy: Double? = null
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
    val parked = db.parkedLocationDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val places = db.savedPlaceDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _activeRide = kotlinx.coroutines.flow.MutableStateFlow<RideTracker.ActiveRideUi?>(null)

    val dashboard: StateFlow<DashboardUi> = combine(
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
        db.rideDao().observeAverageEconomy()
    ) { values ->
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
            avgEconomy = values[11] as Double?
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardUi())

    fun bindTracker(tracker: RideTracker?) {
        boundTracker = tracker
        viewModelScope.launch {
            tracker?.activeRide?.collect { _activeRide.value = it }
        }
    }

    fun startService() {
        ScooterBleService.start(getApplication())
    }

    fun scan() {
        ScooterBleService.start(getApplication(), ScooterBleService.ACTION_START_SCAN)
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

// Helper overload-friendly combine for many flows
private inline fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> combine(
    f1: kotlinx.coroutines.flow.Flow<T1>,
    f2: kotlinx.coroutines.flow.Flow<T2>,
    f3: kotlinx.coroutines.flow.Flow<T3>,
    f4: kotlinx.coroutines.flow.Flow<T4>,
    f5: kotlinx.coroutines.flow.Flow<T5>,
    f6: kotlinx.coroutines.flow.Flow<T6>,
    f7: kotlinx.coroutines.flow.Flow<T7>,
    f8: kotlinx.coroutines.flow.Flow<T8>,
    f9: kotlinx.coroutines.flow.Flow<T9>,
    f10: kotlinx.coroutines.flow.Flow<T10>,
    f11: kotlinx.coroutines.flow.Flow<T11>,
    f12: kotlinx.coroutines.flow.Flow<T12>,
    crossinline transform: suspend (Array<Any?>) -> R
): kotlinx.coroutines.flow.Flow<R> = kotlinx.coroutines.flow.combine(
    listOf(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12)
) { arr -> transform(arr) }
