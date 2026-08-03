package com.itvs.connect.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.itvs.connect.data.PlaceNameResolver
import com.itvs.connect.data.RideEntity
import com.itvs.connect.data.RideStatsCalculator
import com.itvs.connect.ui.components.SectionHeader
import com.itvs.connect.util.Formatters

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RidesScreen(
    rides: List<RideEntity>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onMerge: (List<Long>) -> Unit
) {
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selecting = selected.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        SectionHeader(
            "Rides",
            if (selecting) {
                "${selected.size} selected — merge combines distance, time, and economy"
            } else {
                "Auto-tracked rides with place labels. Long-press to select and merge."
            }
        )

        if (selecting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onMerge(selected.toList())
                        selected = emptySet()
                    },
                    enabled = selected.size >= 2,
                    modifier = Modifier.weight(1f)
                ) { Text("Merge selected") }
                OutlinedButton(
                    onClick = { selected = emptySet() },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
            }
        }

        if (rides.isEmpty()) {
            Text(
                "No rides yet. Connect to your scooter and ride — tracking starts when ignition telemetry appears.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rides, key = { it.id }) { ride ->
                    val isSelected = selected.contains(ride.id)
                    RideRow(
                        ride = ride,
                        selected = isSelected,
                        selecting = selecting,
                        onOpen = { onOpen(ride.id) },
                        onDelete = { onDelete(ride.id) },
                        onLongPress = {
                            selected = if (isSelected) selected - ride.id else selected + ride.id
                        },
                        onToggleSelect = {
                            selected = if (isSelected) selected - ride.id else selected + ride.id
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RideRow(
    ride: RideEntity,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(16.dp)
            )
            .combinedClickable(
                onClick = {
                    if (selecting) onToggleSelect() else onOpen()
                },
                onLongClick = onLongPress
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val title = PlaceNameResolver.displayTitle(ride)
            Text(
                title.ifBlank { Formatters.dateTime(ride.startTimeMs) },
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(6.dp))
            if (title.isNotBlank()) {
                Text(
                    Formatters.dateTime(ride.startTimeMs),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                "${Formatters.km(ride.distanceKm)} · ${Formatters.durationHoursMinutes(ride.durationMs)} · ${Formatters.kmL(ride.approxKmPerLitre)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Avg ${Formatters.speed(ride.avgSpeedKmh)} · Max ${Formatters.speed(ride.maxSpeedKmh)}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (ride.notes.isNotBlank()) {
                Text(ride.notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!selecting) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        } else {
            Text(if (selected) "✓" else "○", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun RideDetailScreen(
    ride: RideEntity?,
    onBack: () -> Unit,
    onSaveLabel: (Long, String) -> Unit = { _, _ -> },
    onEnrichPlaces: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    var sameLocationDialog by remember { mutableStateOf(false) }
    var labelDraft by remember(ride?.id, ride?.label) {
        mutableStateOf(ride?.label.orEmpty())
    }

    LaunchedEffect(ride?.id) {
        val r = ride ?: return@LaunchedEffect
        if ((r.startPlaceName.isNullOrBlank() && r.startLat != null) ||
            (r.endPlaceName.isNullOrBlank() && r.endLat != null)
        ) {
            onEnrichPlaces(r.id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            "← Rides",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onBack)
        )
        Spacer(Modifier.height(12.dp))
        if (ride == null) {
            Text("Ride not found", style = MaterialTheme.typography.bodyMedium)
            return
        }

        val autoTitle = PlaceNameResolver.displayTitle(ride.copy(label = ""))
        Text(
            PlaceNameResolver.displayTitle(ride).ifBlank { Formatters.dateTime(ride.startTimeMs) },
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = labelDraft,
            onValueChange = { labelDraft = it.take(48) },
            label = { Text("Trip label") },
            supportingText = {
                Text(
                    if (autoTitle.isNotBlank()) {
                        "Leave blank to use: $autoTitle"
                    } else {
                        "Optional name for this trip"
                    }
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onSaveLabel(ride.id, labelDraft) },
            enabled = labelDraft.trim() != ride.label.trim(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save label") }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Start", style = MaterialTheme.typography.labelLarge)
                Text(Formatters.dateTime(ride.startTimeMs), style = MaterialTheme.typography.bodyLarge)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("End", style = MaterialTheme.typography.labelLarge)
                Text(Formatters.dateTime(ride.endTimeMs), style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(Modifier.height(20.dp))
        DetailLine("Distance", Formatters.km(ride.distanceKm))
        DetailLine("Duration", Formatters.durationHoursMinutes(ride.durationMs))
        DetailLine("Avg km/L", Formatters.kmL(ride.approxKmPerLitre))
        DetailLine("Fuel spent", Formatters.litres(ride.estimatedLitresUsed))
        DetailLine("Economy source", ride.economySource.replace('_', ' '))
        if (ride.clusterAfeKmL != null) {
            DetailLine("Last cluster AFE", "${ride.clusterAfeKmL} km/L")
        }
        DetailLine("Avg speed", Formatters.speed(ride.avgSpeedKmh))
        DetailLine("Max speed", Formatters.speed(ride.maxSpeedKmh))

        Spacer(Modifier.height(16.dp))
        Text("Locations", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        LocationButton(
            label = ride.startPlaceName?.takeIf { it.isNotBlank() } ?: "Start location",
            lat = ride.startLat,
            lng = ride.startLng
        ) {
            openStartEndMaps(
                context = context,
                startLat = ride.startLat,
                startLng = ride.startLng,
                endLat = ride.endLat,
                endLng = ride.endLng,
                fallbackLabel = "Ride start",
                onSameLocation = { sameLocationDialog = true }
            )
        }
        Spacer(Modifier.height(8.dp))
        LocationButton(
            label = ride.endPlaceName?.takeIf { it.isNotBlank() } ?: "End / parked location",
            lat = ride.endLat,
            lng = ride.endLng
        ) {
            openStartEndMaps(
                context = context,
                startLat = ride.startLat,
                startLng = ride.startLng,
                endLat = ride.endLat,
                endLng = ride.endLng,
                fallbackLabel = "Ride end",
                onSameLocation = { sameLocationDialog = true }
            )
        }

        if (sameLocationDialog) {
            AlertDialog(
                onDismissRequest = { sameLocationDialog = false },
                confirmButton = {
                    TextButton(onClick = { sameLocationDialog = false }) { Text("OK") }
                },
                title = { Text("Same location") },
                text = {
                    Text("Both start and end location are the same — skipping Maps redirect.")
                }
            )
        }
    }
}

@Composable
private fun LocationButton(
    label: String,
    lat: Double?,
    lng: Double?,
    onOpen: () -> Unit
) {
    OutlinedButton(
        onClick = onOpen,
        enabled = lat != null && lng != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(Formatters.latLng(lat, lng), style = MaterialTheme.typography.bodyMedium)
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

/**
 * Opens Maps for start→end when both pins exist.
 * Same coordinates → caller shows a dialog instead of redirecting.
 * Only one pin → drop a single marker.
 */
private fun openStartEndMaps(
    context: android.content.Context,
    startLat: Double?,
    startLng: Double?,
    endLat: Double?,
    endLng: Double?,
    fallbackLabel: String,
    onSameLocation: () -> Unit
) {
    val hasStart = startLat != null && startLng != null
    val hasEnd = endLat != null && endLng != null
    when {
        hasStart && hasEnd -> {
            if (RideStatsCalculator.sameLocation(startLat, startLng, endLat, endLng)) {
                onSameLocation()
            } else {
                openMapsDirections(context, startLat!!, startLng!!, endLat!!, endLng!!)
            }
        }
        hasEnd -> openMapsPin(context, endLat!!, endLng!!, fallbackLabel)
        hasStart -> openMapsPin(context, startLat!!, startLng!!, fallbackLabel)
    }
}

private fun openMapsPin(context: android.content.Context, lat: Double, lng: Double, label: String) {
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.google.android.apps.maps")
    }
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

private fun openMapsDirections(
    context: android.content.Context,
    startLat: Double,
    startLng: Double,
    endLat: Double,
    endLng: Double
) {
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1&origin=$startLat,$startLng&destination=$endLat,$endLng&travelmode=driving"
    )
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}
