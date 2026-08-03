package com.itvs.connect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itvs.connect.ble.ConnectionState
import com.itvs.connect.ui.DashboardUi
import com.itvs.connect.ui.components.MetricRow
import com.itvs.connect.ui.components.MetricTile
import com.itvs.connect.ui.components.SectionHeader
import com.itvs.connect.ui.components.StatusChip
import com.itvs.connect.util.Formatters

@Composable
fun DashboardScreen(
    ui: DashboardUi,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onFindMe: () -> Unit,
    onDropPin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("iTVS Connect", style = MaterialTheme.typography.displayLarge)
        Text(
            "Local scooter companion · no login · no cloud",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        val (statusLabel, accent) = when (ui.connection) {
            ConnectionState.Disconnected -> "Disconnected" to false
            ConnectionState.Scanning -> "Scanning…" to true
            ConnectionState.Connecting -> "Connecting…" to true
            ConnectionState.Authenticating -> "Authenticating…" to true
            is ConnectionState.Connected -> {
                val name = ui.connection.deviceName.ifBlank { "Scooter" }
                "Connected · $name" to true
            }
        }
        StatusChip(statusLabel, accent = accent)
        if (ui.telemetryActive) {
            Spacer(Modifier.height(8.dp))
            StatusChip("Ride mode · auto-tracking", accent = true)
        }

        Spacer(Modifier.height(20.dp))

        ui.activeRide?.let { ride ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(0.12f), RoundedCornerShape(20.dp))
                    .padding(18.dp)
            ) {
                Text("Live ride", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                MetricRow {
                    MetricTile("Distance", Formatters.km(ride.distanceKm), Modifier.weight(1f))
                    MetricTile("Time", Formatters.duration(ride.durationMs), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                MetricRow {
                    MetricTile("Speed", Formatters.speed(ride.currentSpeedKmh), Modifier.weight(1f))
                    MetricTile("Cluster AFE", "${ride.afe ?: "—"} km/L", Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        SectionHeader("Telemetry", "Live values from the SmartXonnect cluster")
        MetricRow {
            MetricTile("Fuel", "${ui.fuelPercent}%", Modifier.weight(1f))
            MetricTile("Odometer", Formatters.km(ui.odometerKm), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        MetricRow {
            MetricTile("Economy", "${ui.afe} km/L", Modifier.weight(1f))
            MetricTile("DTE", "${ui.dte} km", Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("Lifetime", "Stored only on this phone")
        MetricRow {
            MetricTile("Total distance", Formatters.km(ui.totalDistanceKm), Modifier.weight(1f))
            MetricTile("Avg km/L", Formatters.kmL(ui.avgEconomy), Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onFindMe,
                enabled = ui.connection is ConnectionState.Connected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) { Text("Find my scooter") }

            if (ui.connection is ConnectionState.Connected || ui.connection is ConnectionState.Scanning) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect")
                }
            } else {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (ui.settings.scooterMac.isBlank()) "Scan & pair scooter"
                        else "Reconnect ${ui.settings.scooterName.ifBlank { "scooter" }}"
                    )
                }
            }

            OutlinedButton(onClick = onDropPin, modifier = Modifier.fillMaxWidth()) {
                Text("Save parked location")
            }
        }

        if (ui.serviceReminder != 0) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Service reminder flag: ${ui.serviceReminder}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
