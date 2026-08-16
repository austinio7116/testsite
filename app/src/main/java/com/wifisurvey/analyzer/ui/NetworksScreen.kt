package com.wifisurvey.analyzer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifisurvey.analyzer.wifi.AccessPoint
import com.wifisurvey.analyzer.wifi.Band
import com.wifisurvey.analyzer.wifi.CLEAN_24_CHANNELS
import com.wifisurvey.analyzer.wifi.SignalScale
import com.wifisurvey.analyzer.wifi.isOpenNetwork
import kotlin.math.roundToInt

private enum class SortMode(val label: String) {
    SIGNAL("Signal"),
    NAME("Name"),
    CHANNEL("Channel"),
    BAND("Band")
}

@Composable
fun NetworksScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val wifi by viewModel.wifiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val tracked by viewModel.trackedBssid.collectAsStateWithLifecycle()

    var sort by remember { mutableStateOf(SortMode.SIGNAL) }
    var bandFilter by remember { mutableStateOf<Band?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }

    val visible = remember(wifi.accessPoints, sort, bandFilter) {
        wifi.accessPoints
            .filter { bandFilter == null || it.band == bandFilter }
            .let { list ->
                when (sort) {
                    SortMode.SIGNAL -> list.sortedByDescending { it.rssi }
                    SortMode.NAME -> list.sortedBy { it.displayName.lowercase() }
                    SortMode.CHANNEL -> list.sortedBy { it.channel }
                    SortMode.BAND -> list.sortedWith(compareBy({ it.band.ordinal }, { -it.rssi }))
                }
            }
    }

    val trackedAp = visible.firstOrNull { it.bssid == (tracked ?: wifi.connectedBssid) }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionCard(
                title = trackedAp?.let { "Live · ${it.displayName}" } ?: "Live signal"
            ) {
                if (trackedAp == null) {
                    Text(
                        "Tap any network below to watch its signal in real time.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${trackedAp.rssi} dBm",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = SignalColors.forDbm(trackedAp.rssi)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(SignalScale.label(trackedAp.rssi), fontWeight = FontWeight.Medium)
                            Text(
                                SignalScale.usage(trackedAp.rssi),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        wifi.connectedLinkSpeedMbps?.takeIf { trackedAp.isConnected }?.let {
                            StatChip("Link", "$it Mbps")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    RssiSparkline(
                        history = history,
                        modifier = Modifier.fillMaxWidth().height(90.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (trackedAp.isConnected) {
                            "Connected AP — updates every 2 s, unaffected by scan throttling."
                        } else {
                            "Not connected — updates only when the system scans."
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SortMode.entries.forEach { mode ->
                    FilterChip(
                        selected = sort == mode,
                        onClick = { sort = mode },
                        label = { Text(mode.label) }
                    )
                }
                Spacer(Modifier.width(4.dp))
                listOf(null, Band.GHZ_2_4, Band.GHZ_5, Band.GHZ_6).forEach { band ->
                    FilterChip(
                        selected = bandFilter == band,
                        onClick = { bandFilter = band },
                        label = { Text(band?.label ?: "All bands") }
                    )
                }
            }
        }

        item {
            Text(
                "${visible.size} networks visible",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(visible, key = { it.bssid }) { ap ->
            NetworkRow(
                ap = ap,
                expanded = expanded == ap.bssid,
                onClick = {
                    expanded = if (expanded == ap.bssid) null else ap.bssid
                    viewModel.trackAp(ap.bssid)
                }
            )
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun NetworkRow(ap: AccessPoint, expanded: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (ap.isConnected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalBars(ap.rssi)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ap.displayName,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    if (ap.isConnected) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "CONNECTED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    "${ap.band.label} · ch ${ap.channel} · ${ap.security}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${ap.rssi}",
                    fontWeight = FontWeight.Bold,
                    color = SignalColors.forDbm(ap.rssi)
                )
                Text(
                    "dBm",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        StrengthMeter(ap.rssi, Modifier.fillMaxWidth())

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 12.dp)) {
                DetailRow("BSSID", ap.bssid.uppercase(), mono = true)
                if (ap.vendor.isNotBlank()) DetailRow("Vendor", ap.vendor)
                DetailRow("Frequency", "${ap.frequencyMhz} MHz")
                DetailRow("Channel width", "${ap.channelWidthMhz} MHz")
                DetailRow("Standard", ap.standard)
                DetailRow("Quality", "${(ap.quality * 100).roundToInt()}%")
                DetailRow("Distance (line of sight)", formatDistance(ap.estimatedDistanceMeters))
                DetailRow("Last seen", "${ap.lastSeenElapsedMs / 1000} s ago")

                if (ap.band == Band.GHZ_2_4 && ap.channel !in CLEAN_24_CHANNELS) {
                    Spacer(Modifier.height(8.dp))
                    Advice(
                        "Channel ${ap.channel} overlaps its neighbours. On 2.4 GHz only " +
                            "1, 6 and 11 avoid each other.",
                        MaterialTheme.colorScheme.secondary
                    )
                }
                if (isOpenNetwork(ap.security)) {
                    Spacer(Modifier.height(8.dp))
                    Advice(
                        "No link-layer encryption — anything sent over this network is " +
                            "visible to anyone in range.",
                        MaterialTheme.colorScheme.error
                    )
                }
                if (ap.band == Band.GHZ_2_4) {
                    Spacer(Modifier.height(8.dp))
                    Advice(
                        "2.4 GHz travels further but is slower and busier. If this AP also " +
                            "broadcasts on 5 GHz, prefer that within about one room.",
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun Advice(text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(8.dp)
    ) {
        Text(text, fontSize = 11.sp, color = color)
    }
}

private fun formatDistance(meters: Double): String = when {
    meters.isNaN() -> "—"
    meters < 1.0 -> "< 1 m"
    meters < 100.0 -> "≈ ${meters.roundToInt()} m"
    else -> "> 100 m"
}
