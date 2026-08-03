package com.itvs.connect.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itvs.connect.ble.ButtonAction
import com.itvs.connect.ble.CallGesture
import com.itvs.connect.data.AppSettings
import com.itvs.connect.ui.components.SectionHeader

@Composable
fun ButtonsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        SectionHeader(
            "Button mapping",
            "Map Voice-button gestures. Choose Rotate ride stats to cycle ride time, mileage, and Maps ETA on the cluster every 10s (press again to stop)."
        )

        ActionPicker("Single tap", settings.singlePress) {
            onChange { s -> s.copy(singlePress = it) }
        }
        ActionPicker("Double tap", settings.doublePress) {
            onChange { s -> s.copy(doublePress = it) }
        }
        ActionPicker("Triple tap", settings.triplePress) {
            onChange { s -> s.copy(triplePress = it) }
        }
        ActionPicker("Long press", settings.longPress) {
            onChange { s -> s.copy(longPress = it) }
        }
        ActionPicker("2× tap + long", settings.singleLongPress) {
            onChange { s -> s.copy(singleLongPress = it) }
        }
        ActionPicker("3× tap + long", settings.doubleLongPress) {
            onChange { s -> s.copy(doubleLongPress = it) }
        }

        Spacer(Modifier.height(16.dp))
        Text("Speed dial numbers", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.speedDialSingle,
            onValueChange = { v -> onChange { s -> s.copy(speedDialSingle = v) } },
            label = { Text("Single tap dial") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.speedDialLong,
            onValueChange = { v -> onChange { s -> s.copy(speedDialLong = v) } },
            label = { Text("Long press dial") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Text("Call handling", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        GesturePicker("Answer gesture", settings.callAnswerGesture) {
            onChange { s -> s.copy(callAnswerGesture = it) }
        }
        GesturePicker("Decline / end gesture", settings.callDeclineGesture) {
            onChange { s -> s.copy(callDeclineGesture = it) }
        }

        Spacer(Modifier.height(16.dp))
        RowSwitch("Beep on tap", settings.beepEnabled) {
            onChange { s -> s.copy(beepEnabled = it) }
        }
        RowSwitch("Require headphones", settings.requireHeadphones) {
            onChange { s -> s.copy(requireHeadphones = it) }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionPicker(label: String, value: ButtonAction, onSelect: (ButtonAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .padding(vertical = 6.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ButtonAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.displayName) },
                    onClick = {
                        onSelect(action)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GesturePicker(label: String, value: CallGesture, onSelect: (CallGesture) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .padding(vertical = 6.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CallGesture.entries.forEach { gesture ->
                DropdownMenuItem(
                    text = { Text(gesture.displayName) },
                    onClick = {
                        onSelect(gesture)
                        expanded = false
                    }
                )
            }
        }
    }
}
