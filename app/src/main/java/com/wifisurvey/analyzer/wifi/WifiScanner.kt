package com.wifisurvey.analyzer.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Snapshot of everything the UI needs to know about the radio environment. */
data class WifiState(
    val wifiEnabled: Boolean = true,
    val hasPermission: Boolean = false,
    val accessPoints: List<AccessPoint> = emptyList(),
    val connectedBssid: String? = null,
    val connectedSsid: String? = null,
    val connectedRssi: Int? = null,
    val connectedLinkSpeedMbps: Int? = null,
    val lastResultsAgeMs: Long = 0L,
    val scansInLastTwoMinutes: Int = 0,
    val throttled: Boolean = false
)

/**
 * Wraps [WifiManager] with throttle-aware scanning.
 *
 * Android 9 and later cap foreground apps at four `startScan()` calls per
 * two minutes. Rather than fight that, this polls the system's cached scan
 * results continuously (they are refreshed by the platform's own scans) and
 * only requests an explicit scan when the budget allows. The RSSI of the
 * *connected* AP is read separately and is not throttled, which is what makes
 * live surveying responsive.
 */
class WifiScanner(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _state = MutableStateFlow(WifiState())
    val state: StateFlow<WifiState> = _state.asStateFlow()

    private val scanRequestTimes = ArrayDeque<Long>()
    private var pollJob: Job? = null
    private var receiverRegistered = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshFromCache()
        }
    }

    fun start(scope: CoroutineScope) {
        if (!receiverRegistered) {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            ContextCompat.registerReceiver(
                context,
                scanReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                requestScanIfAllowed()
                refreshFromCache()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(scanReceiver) }
            receiverRegistered = false
        }
    }

    /** Ask the platform for a fresh scan, respecting the 4-per-2-minutes budget. */
    fun requestScanIfAllowed(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        while (scanRequestTimes.isNotEmpty() && now - scanRequestTimes.first() > THROTTLE_WINDOW_MS) {
            scanRequestTimes.removeFirst()
        }
        val budgetLeft = scanRequestTimes.size < MAX_SCANS_PER_WINDOW
        val spacedOut = scanRequestTimes.isEmpty() ||
            now - scanRequestTimes.last() >= MIN_SCAN_SPACING_MS
        if (!hasScanPermission()) return
        if (force || (budgetLeft && spacedOut)) {
            @Suppress("DEPRECATION")
            val accepted = runCatching { wifiManager.startScan() }.getOrDefault(false)
            if (accepted) scanRequestTimes.addLast(now)
            _state.value = _state.value.copy(throttled = !accepted)
        }
    }

    private fun hasScanPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, needed) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Read the platform's cached scan list and fold it into [state]. */
    fun refreshFromCache() {
        val permitted = hasScanPermission()
        if (!permitted) {
            _state.value = _state.value.copy(hasPermission = false, wifiEnabled = wifiManager.isWifiEnabled)
            return
        }

        @Suppress("DEPRECATION")
        val info = runCatching { wifiManager.connectionInfo }.getOrNull()
        // A redacted BSSID means the platform withheld it; treat that as "not connected".
        val connectedBssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" }
        val connectedSsid = info?.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

        val results: List<ScanResult> = runCatching { wifiManager.scanResults }
            .getOrDefault(emptyList())

        val now = SystemClock.elapsedRealtime()
        val points = results.mapNotNull { it.toAccessPoint(now, connectedBssid) }
            .sortedByDescending { it.rssi }

        val freshest = points.minOfOrNull { it.lastSeenElapsedMs } ?: 0L

        val activeScans = scanRequestTimes.count { now - it <= THROTTLE_WINDOW_MS }

        _state.value = WifiState(
            wifiEnabled = wifiManager.isWifiEnabled,
            hasPermission = true,
            accessPoints = points,
            connectedBssid = connectedBssid,
            connectedSsid = connectedSsid,
            connectedRssi = info?.rssi?.takeIf { it != -127 && connectedBssid != null },
            connectedLinkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            lastResultsAgeMs = freshest,
            scansInLastTwoMinutes = activeScans,
            throttled = activeScans >= MAX_SCANS_PER_WINDOW
        )
    }

    private fun ScanResult.toAccessPoint(nowElapsedMs: Long, connectedBssid: String?): AccessPoint? {
        val mac = BSSID ?: return null
        val ssidText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
        } else {
            @Suppress("DEPRECATION")
            SSID ?: ""
        }
        val widthMhz = when (channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> 20
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ -> 160
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
            else -> 20
        }
        return AccessPoint(
            bssid = mac,
            ssid = ssidText,
            rssi = level,
            frequencyMhz = frequency,
            channel = channelForFrequency(frequency),
            band = bandForFrequency(frequency),
            channelWidthMhz = widthMhz,
            centerFrequencyMhz = if (centerFreq0 > 0) centerFreq0 else frequency,
            security = securityFromCapabilities(capabilities ?: ""),
            standard = readStandard(),
            vendor = OuiLookup.vendorFor(mac),
            lastSeenElapsedMs = (nowElapsedMs - timestamp / 1000).coerceAtLeast(0),
            isConnected = connectedBssid != null && mac.equals(connectedBssid, ignoreCase = true)
        )
    }

    private fun ScanResult.readStandard(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "—"
        return when (wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (n)"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (ac)"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (ax)"
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                wifiStandard == ScanResult.WIFI_STANDARD_11BE
            ) "Wi-Fi 7 (be)" else "—"
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val THROTTLE_WINDOW_MS = 120_000L
        private const val MAX_SCANS_PER_WINDOW = 4
        private const val MIN_SCAN_SPACING_MS = 28_000L
    }
}

/** Tiny OUI table covering the vendors most likely to be behind a home AP. */
object OuiLookup {
    private val table = mapOf(
        "00:1A:11" to "Google", "F4:F5:E8" to "Google", "3C:28:6D" to "Google",
        "00:17:88" to "Philips", "B8:27:EB" to "Raspberry Pi", "DC:A6:32" to "Raspberry Pi",
        "E4:5F:01" to "Raspberry Pi", "00:1D:7E" to "Cisco-Linksys", "00:23:69" to "Cisco-Linksys",
        "C0:56:27" to "Belkin", "94:10:3E" to "Belkin", "00:14:6C" to "Netgear",
        "20:4E:7F" to "Netgear", "A0:40:A0" to "Netgear", "9C:3D:CF" to "Netgear",
        "00:1F:33" to "Netgear", "C4:04:15" to "Netgear", "00:26:F2" to "Netgear",
        "50:6A:03" to "Netgear", "00:90:4C" to "Epigram", "D8:5D:4C" to "TP-Link",
        "50:C7:BF" to "TP-Link", "EC:08:6B" to "TP-Link", "A4:2B:B0" to "TP-Link",
        "60:32:B1" to "TP-Link", "AC:84:C6" to "TP-Link", "18:D6:C7" to "TP-Link",
        "00:0C:42" to "MikroTik", "48:8F:5A" to "MikroTik", "DC:2C:6E" to "MikroTik",
        "24:A4:3C" to "Ubiquiti", "78:8A:20" to "Ubiquiti", "F0:9F:C2" to "Ubiquiti",
        "68:D7:9A" to "Ubiquiti", "74:83:C2" to "Ubiquiti", "E0:63:DA" to "Ubiquiti",
        "00:1D:D8" to "Microsoft", "70:4F:57" to "Sagemcom", "88:03:55" to "Arris",
        "00:15:96" to "Arris", "3C:7A:8A" to "Arris", "9C:34:26" to "Arris",
        "2C:9E:5F" to "Technicolor", "00:26:44" to "Technicolor", "58:23:8C" to "Technicolor",
        "00:1F:5B" to "Apple", "AC:BC:32" to "Apple", "F0:18:98" to "Apple",
        "D0:81:7A" to "Apple", "A4:83:E7" to "Apple", "00:24:36" to "Apple",
        "00:11:32" to "Synology", "00:E0:4C" to "Realtek", "E8:DE:27" to "TP-Link",
        "80:2A:A8" to "Ubiquiti", "44:D9:E7" to "Ubiquiti", "04:18:D6" to "Ubiquiti",
        "1C:B7:2C" to "ASUS", "2C:56:DC" to "ASUS", "50:46:5D" to "ASUS",
        "AC:9E:17" to "ASUS", "38:D5:47" to "ASUS", "04:D4:C4" to "ASUS",
        "00:1E:2A" to "Netgear", "84:1B:5E" to "Netgear", "10:0D:7F" to "Netgear",
        "FC:EC:DA" to "Ubiquiti", "78:45:58" to "Ubiquiti", "B4:FB:E4" to "Ubiquiti",
        "00:03:7F" to "Atheros", "34:8A:AE" to "Sercomm", "BC:64:4B" to "Aruba",
        "6C:F3:7F" to "Aruba", "00:0B:86" to "Aruba", "94:B4:0F" to "Aruba",
        "00:24:6C" to "Aruba", "F0:5C:19" to "Aruba", "20:4C:03" to "Aruba"
    )

    fun vendorFor(bssid: String): String {
        if (bssid.length < 8) return ""
        val prefix = bssid.substring(0, 8).uppercase()
        return table[prefix] ?: ""
    }
}
