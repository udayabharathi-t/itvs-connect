package com.itvs.connect.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.itvs.connect.ble.ButtonAction
import com.itvs.connect.ble.CallGesture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("itvs_prefs")

data class AppSettings(
    val scooterMac: String = "",
    val scooterName: String = "",
    val tankCapacityLitres: Double = 5.1,
    /** Petrol price in whole rupees per litre. 0 = unset (Trip Cost shows N/A). */
    val fuelCostPerLitre: Int = 0,
    val riderName: String = "iTVS",
    val autoConnect: Boolean = true,
    val appNotificationsEnabled: Boolean = true,
    val beepEnabled: Boolean = true,
    val requireHeadphones: Boolean = false,
    val singlePress: ButtonAction = ButtonAction.TOGGLE_PLAYBACK,
    val doublePress: ButtonAction = ButtonAction.NEXT_TRACK,
    val triplePress: ButtonAction = ButtonAction.PREVIOUS_TRACK,
    val longPress: ButtonAction = ButtonAction.GOOGLE_ASSISTANT,
    val singleLongPress: ButtonAction = ButtonAction.VOLUME_UP,
    val doubleLongPress: ButtonAction = ButtonAction.VOLUME_DOWN,
    val tripleLongPress: ButtonAction = ButtonAction.DO_NOTHING,
    val doublePressWindowMs: Int = 1500,
    val longPressThresholdMs: Int = 1000,
    val cooldownMs: Int = 500,
    val callAnswerGesture: CallGesture = CallGesture.SINGLE_PRESS,
    val callDeclineGesture: CallGesture = CallGesture.DOUBLE_PRESS,
    val speedDialSingle: String = "",
    val speedDialDouble: String = "",
    val speedDialTriple: String = "",
    val speedDialLong: String = "",
    val lastFuelPercent: Int = 0,
    val lastOdometerKm: Double = 0.0,
    val lastAfe: Int = 0,
    val lastDte: Int = 0,
    val isRideModeActive: Boolean = false
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val scooterMac = stringPreferencesKey("scooter_mac")
        val scooterName = stringPreferencesKey("scooter_name")
        val tankCapacity = doublePreferencesKey("tank_capacity")
        val fuelCostPerLitre = intPreferencesKey("fuel_cost_per_litre")
        val riderName = stringPreferencesKey("rider_name")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val appNotifications = booleanPreferencesKey("app_notifications")
        val beep = booleanPreferencesKey("beep_enabled")
        val headphones = booleanPreferencesKey("require_headphones")
        val single = stringPreferencesKey("btn_single")
        val double = stringPreferencesKey("btn_double")
        val triple = stringPreferencesKey("btn_triple")
        val long = stringPreferencesKey("btn_long")
        val singleLong = stringPreferencesKey("btn_single_long")
        val doubleLong = stringPreferencesKey("btn_double_long")
        val tripleLong = stringPreferencesKey("btn_triple_long")
        val doubleWindow = intPreferencesKey("double_window_ms")
        val longThreshold = intPreferencesKey("long_threshold_ms")
        val cooldown = intPreferencesKey("cooldown_ms")
        val answer = stringPreferencesKey("call_answer")
        val decline = stringPreferencesKey("call_decline")
        val dialSingle = stringPreferencesKey("dial_single")
        val dialDouble = stringPreferencesKey("dial_double")
        val dialTriple = stringPreferencesKey("dial_triple")
        val dialLong = stringPreferencesKey("dial_long")
        val fuel = intPreferencesKey("fuel")
        val odo = floatPreferencesKey("odo")
        val afe = intPreferencesKey("afe")
        val dte = intPreferencesKey("dte")
        val rideMode = booleanPreferencesKey("ride_mode")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            scooterMac = p[Keys.scooterMac].orEmpty(),
            scooterName = p[Keys.scooterName].orEmpty(),
            tankCapacityLitres = p[Keys.tankCapacity] ?: 5.1,
            fuelCostPerLitre = p[Keys.fuelCostPerLitre] ?: 0,
            riderName = p[Keys.riderName] ?: "iTVS",
            autoConnect = p[Keys.autoConnect] ?: true,
            appNotificationsEnabled = p[Keys.appNotifications] ?: true,
            beepEnabled = p[Keys.beep] ?: true,
            requireHeadphones = p[Keys.headphones] ?: false,
            singlePress = ButtonAction.fromName(p[Keys.single]),
            doublePress = ButtonAction.fromName(p[Keys.double]),
            triplePress = ButtonAction.fromName(p[Keys.triple]),
            longPress = ButtonAction.fromName(p[Keys.long]),
            singleLongPress = ButtonAction.fromName(p[Keys.singleLong]),
            doubleLongPress = ButtonAction.fromName(p[Keys.doubleLong]),
            tripleLongPress = ButtonAction.fromName(p[Keys.tripleLong]),
            doublePressWindowMs = p[Keys.doubleWindow] ?: 1500,
            longPressThresholdMs = p[Keys.longThreshold] ?: 1000,
            cooldownMs = p[Keys.cooldown] ?: 500,
            callAnswerGesture = CallGesture.fromName(p[Keys.answer]),
            callDeclineGesture = CallGesture.fromName(p[Keys.decline]),
            speedDialSingle = p[Keys.dialSingle].orEmpty(),
            speedDialDouble = p[Keys.dialDouble].orEmpty(),
            speedDialTriple = p[Keys.dialTriple].orEmpty(),
            speedDialLong = p[Keys.dialLong].orEmpty(),
            lastFuelPercent = p[Keys.fuel] ?: 0,
            lastOdometerKm = (p[Keys.odo] ?: 0f).toDouble(),
            lastAfe = p[Keys.afe] ?: 0,
            lastDte = p[Keys.dte] ?: 0,
            isRideModeActive = p[Keys.rideMode] ?: false
        )
    }

    suspend fun setScooter(mac: String, name: String) {
        context.dataStore.edit {
            it[Keys.scooterMac] = mac
            it[Keys.scooterName] = name
        }
    }

    suspend fun clearScooter() {
        context.dataStore.edit {
            it[Keys.scooterMac] = ""
            it[Keys.scooterName] = ""
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = AppSettings(
                scooterMac = prefs[Keys.scooterMac].orEmpty(),
                scooterName = prefs[Keys.scooterName].orEmpty(),
                tankCapacityLitres = prefs[Keys.tankCapacity] ?: 5.1,
                fuelCostPerLitre = prefs[Keys.fuelCostPerLitre] ?: 0,
                riderName = prefs[Keys.riderName] ?: "iTVS",
                autoConnect = prefs[Keys.autoConnect] ?: true,
                appNotificationsEnabled = prefs[Keys.appNotifications] ?: true,
                beepEnabled = prefs[Keys.beep] ?: true,
                requireHeadphones = prefs[Keys.headphones] ?: false,
                singlePress = ButtonAction.fromName(prefs[Keys.single]),
                doublePress = ButtonAction.fromName(prefs[Keys.double]),
                triplePress = ButtonAction.fromName(prefs[Keys.triple]),
                longPress = ButtonAction.fromName(prefs[Keys.long]),
                singleLongPress = ButtonAction.fromName(prefs[Keys.singleLong]),
                doubleLongPress = ButtonAction.fromName(prefs[Keys.doubleLong]),
                tripleLongPress = ButtonAction.fromName(prefs[Keys.tripleLong]),
                doublePressWindowMs = prefs[Keys.doubleWindow] ?: 1500,
                longPressThresholdMs = prefs[Keys.longThreshold] ?: 1000,
                cooldownMs = prefs[Keys.cooldown] ?: 500,
                callAnswerGesture = CallGesture.fromName(prefs[Keys.answer]),
                callDeclineGesture = CallGesture.fromName(prefs[Keys.decline]),
                speedDialSingle = prefs[Keys.dialSingle].orEmpty(),
                speedDialDouble = prefs[Keys.dialDouble].orEmpty(),
                speedDialTriple = prefs[Keys.dialTriple].orEmpty(),
                speedDialLong = prefs[Keys.dialLong].orEmpty(),
                lastFuelPercent = prefs[Keys.fuel] ?: 0,
                lastOdometerKm = (prefs[Keys.odo] ?: 0f).toDouble(),
                lastAfe = prefs[Keys.afe] ?: 0,
                lastDte = prefs[Keys.dte] ?: 0,
                isRideModeActive = prefs[Keys.rideMode] ?: false
            )
            val next = transform(current)
            prefs[Keys.scooterMac] = next.scooterMac
            prefs[Keys.scooterName] = next.scooterName
            prefs[Keys.tankCapacity] = next.tankCapacityLitres
            prefs[Keys.fuelCostPerLitre] = next.fuelCostPerLitre
            prefs[Keys.riderName] = next.riderName
            prefs[Keys.autoConnect] = next.autoConnect
            prefs[Keys.appNotifications] = next.appNotificationsEnabled
            prefs[Keys.beep] = next.beepEnabled
            prefs[Keys.headphones] = next.requireHeadphones
            prefs[Keys.single] = next.singlePress.name
            prefs[Keys.double] = next.doublePress.name
            prefs[Keys.triple] = next.triplePress.name
            prefs[Keys.long] = next.longPress.name
            prefs[Keys.singleLong] = next.singleLongPress.name
            prefs[Keys.doubleLong] = next.doubleLongPress.name
            prefs[Keys.tripleLong] = next.tripleLongPress.name
            prefs[Keys.doubleWindow] = next.doublePressWindowMs
            prefs[Keys.longThreshold] = next.longPressThresholdMs
            prefs[Keys.cooldown] = next.cooldownMs
            prefs[Keys.answer] = next.callAnswerGesture.name
            prefs[Keys.decline] = next.callDeclineGesture.name
            prefs[Keys.dialSingle] = next.speedDialSingle
            prefs[Keys.dialDouble] = next.speedDialDouble
            prefs[Keys.dialTriple] = next.speedDialTriple
            prefs[Keys.dialLong] = next.speedDialLong
            prefs[Keys.fuel] = next.lastFuelPercent
            prefs[Keys.odo] = next.lastOdometerKm.toFloat()
            prefs[Keys.afe] = next.lastAfe
            prefs[Keys.dte] = next.lastDte
            prefs[Keys.rideMode] = next.isRideModeActive
        }
    }

    suspend fun persistTelemetry(fuel: Int, odo: Double, afe: Int, dte: Int) {
        context.dataStore.edit {
            it[Keys.fuel] = fuel
            it[Keys.odo] = odo.toFloat()
            it[Keys.afe] = afe
            it[Keys.dte] = dte
        }
    }

    suspend fun setRideMode(active: Boolean) {
        context.dataStore.edit { it[Keys.rideMode] = active }
    }
}
