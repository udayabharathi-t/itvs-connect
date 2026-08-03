package com.itvs.connect.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.itvs.connect.data.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PreferencesRepository(context)
        val settings = runBlocking { prefs.settings.first() }
        if (settings.autoConnect && settings.scooterMac.isNotBlank()) {
            ScooterBleService.start(context, ScooterBleService.ACTION_AUTO_RECONNECT)
        }
    }
}
