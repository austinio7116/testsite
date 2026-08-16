package com.wifisurvey.analyzer.wifi

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Radio band a network operates in. */
enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
    UNKNOWN("?")
}

/** A single access point as seen by the most recent scan. */
data class AccessPoint(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val band: Band,
    val channelWidthMhz: Int,
    val centerFrequencyMhz: Int,
    val security: String,
    val standard: String,
    val vendor: String,
    val lastSeenElapsedMs: Long,
    val isConnected: Boolean
) {
    /** Display name, falling back to a readable placeholder for hidden networks. */
    val displayName: String get() = if (ssid.isBlank()) "<hidden>" else ssid

    /** Signal quality on 0..1, mapped over the useful −95..−35 dBm window. */
    val quality: Float get() = SignalScale.normalize(rssi)

    /** Rough free-space distance estimate in metres. Indoors this reads long. */
    val estimatedDistanceMeters: Double
        get() = estimateDistance(rssi, frequencyMhz)

    /** Lowest and highest channel occupied, accounting for channel width. */
    val spanMhz: IntRange
        get() {
            val centre = if (centerFrequencyMhz > 0) centerFrequencyMhz else frequencyMhz
            val half = channelWidthMhz / 2
            return (centre - half)..(centre + half)
        }
}

/** Maps RSSI in dBm onto a 0..1 scale and onto human labels. */
object SignalScale {
    const val MIN_DBM = -95f
    const val MAX_DBM = -35f

    fun normalize(rssi: Int): Float =
        ((rssi - MIN_DBM) / (MAX_DBM - MIN_DBM)).coerceIn(0f, 1f)

    fun label(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -60 -> "Very good"
        rssi >= -67 -> "Good"
        rssi >= -75 -> "Fair"
        rssi >= -85 -> "Weak"
        else -> "Unusable"
    }

    /**
     * What the signal will actually support. −67 dBm is the usual planning
     * threshold for voice/video, −70 dBm for reliable browsing.
     */
    fun usage(rssi: Int): String = when {
        rssi >= -60 -> "Streaming, calls, gaming"
        rssi >= -67 -> "Video calls, HD streaming"
        rssi >= -75 -> "Browsing, SD streaming"
        rssi >= -85 -> "Slow, drops likely"
        else -> "No usable service"
    }
}

/** Channel number for a centre frequency in MHz, or 0 when unknown. */
fun channelForFrequency(freqMhz: Int): Int = when {
    freqMhz == 2484 -> 14
    freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
    freqMhz == 5935 -> 2
    freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
    freqMhz in 5160..5885 -> (freqMhz - 5000) / 5
    else -> 0
}

fun bandForFrequency(freqMhz: Int): Band = when {
    freqMhz in 2400..2500 -> Band.GHZ_2_4
    freqMhz in 4900..5900 -> Band.GHZ_5
    freqMhz in 5925..7125 -> Band.GHZ_6
    else -> Band.UNKNOWN
}

/**
 * Free-space path loss inverted for distance, assuming a 20 dBm transmitter.
 * Walls and furniture add loss, so real distances are shorter than this.
 */
fun estimateDistance(rssi: Int, freqMhz: Int): Double {
    if (freqMhz <= 0) return Double.NaN
    val exponent = (27.55 - 20.0 * log10(freqMhz.toDouble()) + abs(rssi.toDouble())) / 20.0
    return 10.0.pow(exponent)
}

/** Human-readable security from the raw capabilities string. */
fun securityFromCapabilities(capabilities: String): String {
    val caps = capabilities.uppercase()
    return when {
        caps.contains("SAE") && caps.contains("PSK") -> "WPA2/3"
        caps.contains("SAE") -> "WPA3"
        caps.contains("OWE") -> "Enhanced Open"
        caps.contains("EAP_SUITE_B") -> "WPA3-Enterprise"
        caps.contains("EAP") -> "WPA-Enterprise"
        caps.contains("WPA2") || caps.contains("RSN") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        caps.contains("WEP") -> "WEP (insecure)"
        else -> "Open"
    }
}

/** True for networks that offer no link-layer encryption. */
fun isOpenNetwork(security: String): Boolean =
    security == "Open" || security.startsWith("WEP")

/** Non-overlapping 2.4 GHz channels — the only three that do not collide. */
val CLEAN_24_CHANNELS = setOf(1, 6, 11)
