package com.itvs.connect

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itvs.connect.ble.ScooterBleService
import com.itvs.connect.ui.AppViewModel
import com.itvs.connect.ui.screens.ButtonsScreen
import com.itvs.connect.ui.screens.DashboardScreen
import com.itvs.connect.ui.screens.MoreScreen
import com.itvs.connect.ui.screens.RideDetailScreen
import com.itvs.connect.ui.screens.RidesScreen
import com.itvs.connect.ui.theme.ItvsTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScooterBleService.LocalBinder
            vm.bindTracker(binder.getService().tracker())
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            vm.bindTracker(null)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.startService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        setContent {
            ItvsTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, ScooterBleService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            vm.startService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    var tab by remember { mutableIntStateOf(0) }
    val dashboard by vm.dashboard.collectAsState()
    val rides by vm.rides.collectAsState()
    val parked by vm.parked.collectAsState()
    val places by vm.places.collectAsState()
    val settings by vm.settings.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = {
                        tab = 0
                        nav.navigate("dashboard") { launchSingleTop = true }
                    },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Dash") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = {
                        tab = 1
                        nav.navigate("rides") { launchSingleTop = true }
                    },
                    icon = { Icon(Icons.Default.Route, contentDescription = null) },
                    label = { Text("Rides") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = {
                        tab = 2
                        nav.navigate("buttons") { launchSingleTop = true }
                    },
                    icon = { Icon(Icons.Default.TouchApp, contentDescription = null) },
                    label = { Text("Buttons") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = {
                        tab = 3
                        nav.navigate("more") { launchSingleTop = true }
                    },
                    icon = { Icon(Icons.Default.MoreHoriz, contentDescription = null) },
                    label = { Text("More") }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    ui = dashboard,
                    onScan = vm::scan,
                    onDisconnect = vm::disconnect,
                    onFindMe = vm::findMe,
                    onDropPin = vm::dropPin,
                    onConnectMac = vm::connectMac
                )
            }
            composable("rides") {
                RidesScreen(
                    rides = rides,
                    onOpen = { id -> nav.navigate("ride/$id") },
                    onDelete = vm::deleteRide,
                    onMerge = vm::mergeRides
                )
            }
            composable(
                "ride/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                val ride by vm.ride(id).collectAsState()
                RideDetailScreen(
                    ride = ride,
                    onBack = { nav.popBackStack() },
                    onSaveLabel = vm::updateRideLabel,
                    onEnrichPlaces = vm::enrichRidePlaceNames
                )
            }
            composable("buttons") {
                ButtonsScreen(settings = settings, onChange = vm::updateSettings)
            }
            composable("more") {
                MoreScreen(
                    settings = settings,
                    parked = parked,
                    places = places,
                    onChange = vm::updateSettings,
                    onClearScooter = vm::clearScooter,
                    onSavePlace = vm::savePlace,
                    onDeletePlace = vm::deletePlace
                )
            }
        }
    }
}
