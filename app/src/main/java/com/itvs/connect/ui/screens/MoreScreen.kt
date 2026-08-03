package com.itvs.connect.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.itvs.connect.data.AppSettings
import com.itvs.connect.data.ParkedLocationEntity
import com.itvs.connect.data.SavedPlaceEntity
import com.itvs.connect.ui.components.SectionHeader
import com.itvs.connect.util.Formatters

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        SectionHeader("More", "Local settings, parked history, bookmarks. Navigation arrives in v2.")

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
            supportingText = { Text("Used to estimate per-ride km/L from fuel-bar changes") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        RowSwitch("Auto-connect on boot", settings.autoConnect) {
            onChange { s -> s.copy(autoConnect = it) }
        }
        RowSwitch("Mirror app notifications", settings.appNotificationsEnabled) {
            onChange { s -> s.copy(appNotificationsEnabled = it) }
        }

        Spacer(Modifier.height(12.dp))
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
        Text("Parked locations", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (parked.isEmpty()) {
            Text("No parked pins yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            parked.take(20).forEach { loc ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(Formatters.dateTime(loc.timestampMs), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "%.5f, %.5f%s".format(
                            loc.latitude,
                            loc.longitude,
                            if (loc.isManual) " · manual" else ""
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            val uri = Uri.parse(
                                "geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}"
                            )
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        },
                        modifier = Modifier.padding(top = 6.dp)
                    ) { Text("Open in maps") }
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
