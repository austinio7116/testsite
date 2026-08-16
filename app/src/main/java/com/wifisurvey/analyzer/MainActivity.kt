package com.wifisurvey.analyzer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wifisurvey.analyzer.ui.AppViewModel
import com.wifisurvey.analyzer.ui.ChannelsScreen
import com.wifisurvey.analyzer.ui.CoverageScreen
import com.wifisurvey.analyzer.ui.NetworksScreen
import com.wifisurvey.analyzer.ui.SectionCard
import com.wifisurvey.analyzer.ui.SurveyScreen
import com.wifisurvey.analyzer.ui.WiFiSurveyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WiFiSurveyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    SURVEY("Survey", Icons.Filled.Map),
    COVERAGE("Coverage", Icons.Filled.GridOn),
    NETWORKS("Networks", Icons.Filled.Wifi),
    CHANNELS("Channels", Icons.Filled.BarChart)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val viewModel: AppViewModel = viewModel()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val wifi by viewModel.wifiState.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.SURVEY) }
    var granted by remember { mutableStateOf(hasScanPermission(context)) }
    val snackbar = remember { SnackbarHostState() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = hasScanPermission(context)
        if (granted) viewModel.forceScan()
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requiredPermissions())
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.survey.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (wifi.wifiEnabled) {
                                "${wifi.accessPoints.size} networks · " +
                                    "${ui.survey.samples.size} samples"
                            } else {
                                "Wi-Fi is off"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label, fontSize = 10.sp) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!granted) {
                PermissionGate(
                    onRequest = { launcher.launch(requiredPermissions()) },
                    modifier = Modifier.padding(12.dp)
                )
            } else if (!wifi.wifiEnabled) {
                WifiOffNotice(Modifier.padding(12.dp))
            } else if (needsLocationServices(context)) {
                LocationServicesNotice(
                    onOpenSettings = {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    modifier = Modifier.padding(12.dp)
                )
            }

            when (tab) {
                Tab.SURVEY -> SurveyScreen(viewModel, Modifier.fillMaxSize())
                Tab.COVERAGE -> CoverageScreen(viewModel, Modifier.fillMaxSize())
                Tab.NETWORKS -> NetworksScreen(viewModel, Modifier.fillMaxSize())
                Tab.CHANNELS -> ChannelsScreen(viewModel, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Text("Permission needed", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "Android requires the \"nearby Wi-Fi devices\" permission before any app " +
                    "can read scan results. This app never uses it to derive your location."
            } else {
                "Android ties Wi-Fi scan results to location permission — without it the " +
                    "system returns an empty list to every app."
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onRequest) { Text("Grant permission") }
    }
}

@Composable
private fun WifiOffNotice(modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Text("Wi-Fi is switched off", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Turn Wi-Fi on to scan. It does not need to be connected to anything.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LocationServicesNotice(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Text("Location services are off", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "On this Android version the system withholds scan results until location " +
                "services are enabled device-wide.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenSettings) { Text("Open location settings") }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
}.toTypedArray()

private fun hasScanPermission(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    return ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED
}

/** Below Android 13 the platform also insists location services are switched on. */
private fun needsLocationServices(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return false
    return !manager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
        !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}
