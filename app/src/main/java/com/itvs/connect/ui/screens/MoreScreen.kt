package com.itvs.connect.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.itvs.connect.ble.MapsNavigationStore
import com.itvs.connect.ble.MapsNotificationHarvester
import com.itvs.connect.ble.NotificationMirrorService
import com.itvs.connect.data.AppSettings
import com.itvs.connect.data.ParkedLocationEntity
import com.itvs.connect.data.SavedPlaceEntity
import com.itvs.connect.ui.components.SectionHeader
import com.itvs.connect.util.Formatters
import kotlinx.coroutines.delay

@Composable
fun MoreScreen(
    settings: AppSettings,
    parked: List<ParkedLocationEntity>,
    places: List<SavedPlaceEntity>,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onClearScooter: () -> Unit,
    onSavePlace: (String, Double, Double) -> Unit,
    onDeletePlace: (Long) -> Unit
) {
    val context = LocalContext.current
    var placeName by remember { mutableStateOf("") }
    var mapsAccessOn by remember {
        mutableStateOf(MapsNotificationHarvester.isNotificationAccessEnabled(context))
    }
    var mapsDebug by remember { mutableStateOf(MapsNotificationHarvester.debug) }
    var mapsSnap by remember { mutableStateOf(MapsNavigationStore.snapshot) }

    LaunchedEffect(Unit) {
        while (true) {
            MapsNotificationHarvester.refreshListenerFlag(context)
            NotificationMirrorService.requestMapsPoll()
            mapsAccessOn = MapsNotificationHarvester.isNotificationAccessEnabled(context)
            mapsDebug = MapsNotificationHarvester.debug
            mapsSnap = MapsNavigationStore.snapshot
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        SectionHeader("More", "Local settings, parked history, bookmarks, and Maps nav harvest.")

        Text(
            "Tested on the TVS Jupiter 110 cc. Other SmartXonnect bikes are unverified — " +
                "more rider reports will help expand support.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text("Paired scooter", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (settings.scooterMac.isBlank()) "None paired yet"
            else "${settings.scooterName.ifBlank { "Scooter" }}\n${settings.scooterMac}",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClearScooter, modifier = Modifier.fillMaxWidth()) {
            Text("Forget scooter")
        }

        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = settings.riderName,
            onValueChange = { v -> onChange { s -> s.copy(riderName = v.take(16)) } },
            label = { Text("Rider name on cluster") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.tankCapacityLitres.toString(),
            onValueChange = { v ->
                v.toDoubleOrNull()?.let { d ->
                    onChange { s -> s.copy(tankCapacityLitres = d.coerceIn(1.0, 20.0)) }
                }
            },
            label = { Text("Fuel tank capacity (L)") },
            supportingText = {
                Text("Fallback only when no live km/L samples were captured during a ride")
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = if (settings.fuelCostPerLitre > 0) {
                settings.fuelCostPerLitre.toString()
            } else {
                ""
            },
            onValueChange = { v ->
                val digits = v.filter { it.isDigit() }.take(4)
                onChange { s ->
                    s.copy(fuelCostPerLitre = digits.toIntOrNull()?.coerceIn(0, 9999) ?: 0)
                }
            },
            label = { Text("Fuel cost (Rs / litre)") },
            supportingText = {
                Text("Used for approx Trip Cost = distance ÷ trip km/L × this price (whole rupees)")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))
        RowSwitch("Auto-reconnect", settings.autoConnect) {
            onChange { s -> s.copy(autoConnect = it) }
        }
        Text(
            if (settings.scooterMac.isBlank()) {
                "Pair once (scan & connect). After that, the app reconnects when you open it, on boot, or when Bluetooth turns on — scooter cluster must be ON, and force-stop official TVS Connect."
            } else {
                "Will auto-reconnect to ${settings.scooterName.ifBlank { "saved scooter" }} (${settings.scooterMac}). Cluster ON · TVS Connect force-stopped."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        RowSwitch("Mirror app notifications", settings.appNotificationsEnabled) {
            onChange { s -> s.copy(appNotificationsEnabled = it) }
        }

        Spacer(Modifier.height(12.dp))
        Text("Maps navigation (no API key)", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("Notification access: ")
                append(if (mapsAccessOn) "On" else "Off — required")
                append('\n')
                append("Next turn: ")
                append(mapsSnap.nextTurnOrNa())
                append(" · Dest left: ")
                append(mapsSnap.distanceOrNa())
                append(" · Time left: ")
                append(mapsSnap.etaOrNa())
                append('\n')
                when {
                    !mapsAccessOn ->
                        append(
                            "If it says Controlled by Restricted setting: " +
                                "Settings → Apps → iTVS Connect → ⋮ → Allow restricted settings, " +
                                "then enable Notification access."
                        )
                    mapsDebug.mapsNotifSeen && mapsDebug.lastError != null ->
                        append(mapsDebug.lastError)
                    mapsSnap.isNavigating -> {
                        append("Nav active")
                        if (mapsSnap.isApproachLock) append(" · approach lock (<200 m)")
                        val preview = mapsDebug.lastRawPreview.ifBlank { mapsSnap.rawPreview }
                        if (preview.isNotBlank()) append(" · $preview")
                    }
                    mapsDebug.mapsNotifSeen ->
                        append("Harvested: ${mapsDebug.lastRawPreview.ifBlank { mapsSnap.rawPreview }}")
                    mapsDebug.lastError != null ->
                        append(mapsDebug.lastError)
                    else ->
                        append("Start turn-by-turn navigation in Google Maps while riding.")
                }
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Notification access settings") }

        Button(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("Disable battery optimizations") }

        Spacer(Modifier.height(24.dp))
        Text("Last parked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (parked.isEmpty()) {
            Text("No parked pin yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val loc = parked.first()
            OutlinedButton(
                onClick = {
                    val uri = Uri.parse(
                        "geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}(Last parked)"
                    )
                    val maps = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
                    runCatching { context.startActivity(maps) }.onFailure {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        loc.label.ifBlank { Formatters.dateTime(loc.timestampMs) },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (loc.label.isNotBlank()) {
                        Text(
                            Formatters.dateTime(loc.timestampMs),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "%.5f, %.5f%s".format(
                            loc.latitude,
                            loc.longitude,
                            if (loc.isManual) " · manual" else ""
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Tap to open in Google Maps", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Saved places", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = placeName,
            onValueChange = { placeName = it },
            label = { Text("Bookmark name (uses latest parked pin)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val latest = parked.firstOrNull() ?: return@Button
                if (placeName.isBlank()) return@Button
                onSavePlace(placeName.trim(), latest.latitude, latest.longitude)
                placeName = ""
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) { Text("Bookmark latest parked location") }

        places.forEach { place ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "%.5f, %.5f".format(place.latitude, place.longitude),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = { onDeletePlace(place.id) }) { Text("Delete") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "v1 scope: BLE connect, find-me, telemetry, auto ride log, button/media/call, notification mirror, parked history.\nv2: cluster navigation HUD.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
