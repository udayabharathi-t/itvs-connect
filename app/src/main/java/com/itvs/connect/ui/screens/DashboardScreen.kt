package com.itvs.connect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itvs.connect.ble.ClusterStatsRotator
import com.itvs.connect.ble.ConnectionState
import com.itvs.connect.ble.DiscoveredDevice
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
    onDropPin: () -> Unit,
    onConnectMac: (String) -> Unit,
    onShowClusterPage: (Int) -> Unit = {},
    onStopClusterHud: () -> Unit = {}
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

        val (statusLabel, accent) = when (val c = ui.connection) {
            ConnectionState.Disconnected -> "Disconnected" to false
            ConnectionState.Scanning -> "Scanning…" to true
            ConnectionState.Connecting -> "Connecting…" to true
            ConnectionState.Authenticating -> "Authenticating…" to true
            is ConnectionState.Connected -> {
                val name = c.deviceName.ifBlank { "Scooter" }
                "Connected · $name" to true
            }
            is ConnectionState.Failed -> "Needs attention" to false
        }
        StatusChip(statusLabel, accent = accent)
        if (ui.statusMessage.isNotBlank()) {
            Text(
                ui.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
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
                    MetricTile("Avg speed", Formatters.speed(ride.avgSpeedKmh), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                MetricRow {
                    MetricTile(
                        "Live km/L",
                        (ride.afe?.takeIf { it in 1..99 }?.toString() ?: "—") + " km/L",
                        Modifier.weight(1f)
                    )
                    MetricTile(
                        "Trip km/L",
                        (ride.tripKmPerLitre?.takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "—") +
                            " km/L",
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                val tripCost = ClusterStatsRotator.approxTripCostRupees(
                    distanceKm = ride.distanceKm,
                    tripKmPerLitre = ride.tripKmPerLitre,
                    fuelCostPerLitre = ui.settings.fuelCostPerLitre
                )
                MetricRow {
                    MetricTile(
                        "Trip Cost",
                        if (tripCost != null) "Rs $tripCost" else "—",
                        Modifier.weight(1f)
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (ui.connection is ConnectionState.Connected && ui.telemetryActive) {
            SectionHeader(
                "Cluster display",
                if (ui.clusterHudRunning) {
                    "Showing on scooter · tap to switch (Voice button optional)"
                } else {
                    "Tap a stat to show it on the scooter cluster"
                }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ClusterStatsRotator.PAGE_LABELS.forEachIndexed { index, label ->
                    val selected = ui.clusterHudRunning && ui.clusterPageIndex == index
                    val colors = if (selected) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Button(
                        onClick = { onShowClusterPage(index) },
                        colors = colors,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(label) }
                }
            }
            if (ui.clusterHudRunning) {
                TextButton(onClick = onStopClusterHud) {
                    Text("Stop cluster display")
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
            MetricTile(
                "Avg km/L",
                if (ui.afe in 1..99) "${ui.afe} km/L" else "— km/L",
                Modifier.weight(1f)
            )
            MetricTile("DTE", "${ui.dte} km", Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("Lifetime", "Stored only on this phone")
        MetricRow {
            MetricTile("Total distance", Formatters.km(ui.totalDistanceKm), Modifier.weight(1f))
            MetricTile("Avg km/L", Formatters.kmL(ui.avgEconomy), Modifier.weight(1f))
        }

        if (ui.discovered.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionHeader(
                "Nearby devices",
                "Tap your scooter. Prefer rows marked Likely. Force-stop TVS Connect first."
            )
            ui.discovered.forEach { device ->
                DiscoveredRow(device) { onConnectMac(device.mac) }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onFindMe,
                enabled = ui.connection is ConnectionState.Connected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) { Text("Find my scooter") }

            when (ui.connection) {
                is ConnectionState.Connected,
                ConnectionState.Scanning,
                ConnectionState.Connecting,
                ConnectionState.Authenticating -> {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel / Disconnect")
                    }
                }
                else -> {
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (ui.settings.scooterMac.isBlank()) "Scan & pair scooter"
                            else "Reconnect ${ui.settings.scooterName.ifBlank { "scooter" }}"
                        )
                    }
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

@Composable
private fun DiscoveredRow(device: DiscoveredDevice, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(
            buildString {
                append(device.name)
                if (device.likelyScooter) append(" · Likely scooter")
            },
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            "${device.mac} · RSSI ${device.rssi} dBm",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
