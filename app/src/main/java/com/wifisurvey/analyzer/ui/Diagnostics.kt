package com.wifisurvey.analyzer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifisurvey.analyzer.wifi.WifiState

private val Good = Color(0xFF48D17A)
private val Bad = Color(0xFFE8622A)

/**
 * Shows exactly why no networks are listed.
 *
 * An empty Wi-Fi list has several unrelated causes — permission refused,
 * location services off, radio disabled, platform throttling — and they are
 * indistinguishable from a broken app unless the state is put on screen.
 */
@Composable
fun DiagnosticsCard(
    wifi: WifiState,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    SectionCard("Why no networks?", modifier) {
        Check("Wi-Fi radio on", wifi.wifiEnabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Check("Nearby-devices permission", wifi.nearbyPermission)
        }
        Check("Location permission", wifi.locationPermission)
        Check("Location services on", wifi.locationServicesOn)
        Check("Scanner running", wifi.pollCount > 0)

        Spacer(Modifier.height(8.dp))
        Line("Entries returned by Android", wifi.rawResultCount.toString())
        Line("Refresh cycles completed", wifi.pollCount.toString())
        Line("Scans requested (last 2 min)", "${wifi.scansInLastTwoMinutes} of 4")
        wifi.lastError?.let { error ->
            Spacer(Modifier.height(6.dp))
            Text(error, fontSize = 11.sp, color = Bad, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            when {
                !wifi.wifiEnabled ->
                    "Turn Wi-Fi on. It does not need to be connected to anything."
                !wifi.locationServicesOn ->
                    "Android hides scan results while location services are off, even " +
                        "when the app holds every permission. Turn them on."
                !wifi.nearbyPermission && !wifi.locationPermission ->
                    "Grant location or nearby-devices permission. On Android 12 and " +
                        "below it must be \"Precise\", not \"Approximate\"."
                wifi.pollCount == 0L ->
                    "The scanner never started — please report this."
                wifi.rawResultCount == 0 ->
                    "Android accepted the request but returned nothing. Toggle Wi-Fi off " +
                        "and on, then tap Rescan."
                else -> "Scanning normally."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRescan) { Text("Rescan") }
            OutlinedButton(onClick = { context.openLocationSettings() }) { Text("Location") }
            OutlinedButton(onClick = { context.openAppSettings() }) { Text("Permissions") }
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) Good else Bad,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun Context.openLocationSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}
