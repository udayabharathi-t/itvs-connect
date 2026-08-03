package com.itvs.connect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itvs.connect.data.RideEntity
import com.itvs.connect.ui.components.SectionHeader
import com.itvs.connect.util.Formatters

@Composable
fun RidesScreen(
    rides: List<RideEntity>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        SectionHeader(
            "Rides",
            "Every ignition automatically starts a ride. Distance, time, and approx km/L are stored locally."
        )
        if (rides.isEmpty()) {
            Text(
                "No rides yet. Connect to your scooter and ride — tracking starts when ignition telemetry appears.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rides, key = { it.id }) { ride ->
                    RideRow(ride, onOpen = { onOpen(ride.id) }, onDelete = { onDelete(ride.id) })
                }
            }
        }
    }
}

@Composable
private fun RideRow(ride: RideEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(Formatters.dateTime(ride.startTimeMs), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "${Formatters.km(ride.distanceKm)} · ${Formatters.duration(ride.durationMs)} · ${Formatters.kmL(ride.approxKmPerLitre)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Avg ${Formatters.speed(ride.avgSpeedKmh)} · Max ${Formatters.speed(ride.maxSpeedKmh)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
    }
}

@Composable
fun RideDetailScreen(ride: RideEntity?, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            "Ride detail",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.clickable(onClick = onBack)
        )
        Spacer(Modifier.height(8.dp))
        if (ride == null) {
            Text("Ride not found", style = MaterialTheme.typography.bodyMedium)
            return
        }
        Text(Formatters.dateTime(ride.startTimeMs), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        DetailLine("Distance", Formatters.km(ride.distanceKm))
        DetailLine("Duration", Formatters.duration(ride.durationMs))
        DetailLine("Approx economy", Formatters.kmL(ride.approxKmPerLitre))
        DetailLine("Economy source", ride.economySource)
        DetailLine("Cluster AFE", ride.clusterAfeKmL?.let { "$it km/L" } ?: "—")
        DetailLine("Litres used (est.)", ride.estimatedLitresUsed?.let { "%.2f L".format(it) } ?: "—")
        DetailLine("Avg speed", Formatters.speed(ride.avgSpeedKmh))
        DetailLine("Max speed", Formatters.speed(ride.maxSpeedKmh))
        DetailLine("Start odo", ride.startOdometerKm?.let { Formatters.km(it) } ?: "—")
        DetailLine("End odo", ride.endOdometerKm?.let { Formatters.km(it) } ?: "—")
        DetailLine("Fuel start → end", "${ride.startFuelPercent ?: "—"}% → ${ride.endFuelPercent ?: "—"}%")
        if (ride.endLat != null && ride.endLng != null) {
            DetailLine("Parked", "%.5f, %.5f".format(ride.endLat, ride.endLng))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
