package com.wifisurvey.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifisurvey.analyzer.wifi.AccessPoint
import com.wifisurvey.analyzer.wifi.Band
import com.wifisurvey.analyzer.wifi.CLEAN_24_CHANNELS
import com.wifisurvey.analyzer.wifi.SignalScale
import com.wifisurvey.analyzer.wifi.channelForFrequency

@Composable
fun ChannelsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val wifi by viewModel.wifiState.collectAsStateWithLifecycle()

    val byBand = remember(wifi.accessPoints) {
        wifi.accessPoints.groupBy { it.band }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard("Pick a clear channel") {
                val recommendation = remember(wifi.accessPoints) {
                    recommend24Channel(wifi.accessPoints)
                }
                Text(recommendation, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Congestion costs more than distance in a busy building. Two APs sharing " +
                        "a channel take turns; two on overlapping channels talk over each other.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        listOf(Band.GHZ_2_4, Band.GHZ_5, Band.GHZ_6).forEach { band ->
            val aps = byBand[band].orEmpty()
            if (aps.isNotEmpty()) {
                item {
                    SectionCard("${band.label} · ${aps.size} networks") {
                        ChannelChart(
                            aps = aps,
                            band = band,
                            modifier = Modifier.fillMaxWidth().height(190.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        ChannelCounts(aps)
                    }
                }
            }
        }

        if (byBand.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        "No networks detected yet. Make sure Wi-Fi is on and permissions " +
                            "are granted.",
                        fontSize = 13.sp
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

/**
 * Signal-vs-frequency chart. Each network is a hump spanning the spectrum it
 * actually occupies, so overlaps are visible rather than implied.
 */
@Composable
private fun ChannelChart(aps: List<AccessPoint>, band: Band, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val range = remember(band, aps) { frequencyRange(band, aps) }

    Canvas(modifier) {
        val leftPad = 30f
        val bottomPad = 22f
        val topPad = 6f
        val plotW = size.width - leftPad
        val plotH = size.height - bottomPad - topPad
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        fun xFor(freq: Float): Float =
            leftPad + ((freq - range.first) / (range.second - range.first)) * plotW

        fun yFor(dbm: Float): Float {
            val t = ((dbm - SignalScale.MIN_DBM) / (SignalScale.MAX_DBM - SignalScale.MIN_DBM))
                .coerceIn(0f, 1f)
            return topPad + plotH - t * plotH
        }

        // Horizontal reference lines.
        listOf(-90f, -75f, -67f, -50f).forEach { level ->
            val y = yFor(level)
            drawLine(axisColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f)
            val layout = measurer.measure(
                "${level.toInt()}",
                TextStyle(fontSize = 8.sp, color = labelColor)
            )
            drawText(layout, topLeft = Offset(2f, y - layout.size.height / 2f))
        }

        // Weakest first so strong networks draw on top.
        aps.sortedBy { it.rssi }.forEach { ap ->
            val centre = if (ap.centerFrequencyMhz > 0) {
                ap.centerFrequencyMhz.toFloat()
            } else {
                ap.frequencyMhz.toFloat()
            }
            val halfWidth = ap.channelWidthMhz / 2f
            val left = xFor(centre - halfWidth)
            val right = xFor(centre + halfWidth)
            val peakX = xFor(centre)
            val peakY = yFor(ap.rssi.toFloat())
            val baseY = topPad + plotH

            val color = SignalColors.forDbm(ap.rssi)
            val path = Path().apply {
                moveTo(left, baseY)
                quadraticBezierTo(left + (peakX - left) * 0.45f, baseY, peakX, peakY)
                quadraticBezierTo(right - (right - peakX) * 0.45f, baseY, right, baseY)
                close()
            }
            drawPath(path, color = color.copy(alpha = 0.16f))
            drawPath(path, color = color.copy(alpha = 0.85f), style = Stroke(width = 1.8f))

            if (ap.rssi > -80) {
                val label = ap.displayName.take(12)
                val layout = measurer.measure(
                    label,
                    TextStyle(fontSize = 8.sp, color = color, fontWeight = FontWeight.Medium)
                )
                drawText(
                    layout,
                    topLeft = Offset(
                        (peakX - layout.size.width / 2f).coerceIn(leftPad, size.width - layout.size.width),
                        (peakY - layout.size.height - 2f).coerceAtLeast(0f)
                    )
                )
            }
        }

        // Baseline + channel ticks.
        val baseY = topPad + plotH
        drawLine(axisColor, Offset(leftPad, baseY), Offset(size.width, baseY), strokeWidth = 1.5f)

        channelTicks(band).forEach { (channel, freq) ->
            if (freq < range.first || freq > range.second) return@forEach
            val x = xFor(freq.toFloat())
            val highlighted = band == Band.GHZ_2_4 && channel in CLEAN_24_CHANNELS
            drawLine(
                color = if (highlighted) Color(0xFF48D17A).copy(alpha = 0.5f) else axisColor,
                start = Offset(x, baseY),
                end = Offset(x, baseY + 4f),
                strokeWidth = if (highlighted) 2f else 1f,
                cap = StrokeCap.Round
            )
            val layout = measurer.measure(
                channel.toString(),
                TextStyle(
                    fontSize = 8.sp,
                    color = if (highlighted) Color(0xFF48D17A) else labelColor
                )
            )
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, baseY + 5f))
        }
    }
}

@Composable
private fun ChannelCounts(aps: List<AccessPoint>) {
    val counts = remember(aps) {
        aps.groupingBy { it.channel }.eachCount().toList().sortedByDescending { it.second }
    }
    val busiest = counts.take(4)
    Text(
        "Busiest: " + busiest.joinToString("  ") { "ch ${it.first} (${it.second})" },
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Visible frequency window for a band, padded to include every AP present. */
private fun frequencyRange(band: Band, aps: List<AccessPoint>): Pair<Float, Float> {
    val default = when (band) {
        Band.GHZ_2_4 -> 2400f to 2500f
        Band.GHZ_5 -> 5150f to 5895f
        Band.GHZ_6 -> 5925f to 7125f
        Band.UNKNOWN -> 2400f to 2500f
    }
    if (aps.isEmpty()) return default
    val lowest = aps.minOf { it.spanMhz.first }.toFloat() - 15f
    val highest = aps.maxOf { it.spanMhz.last }.toFloat() + 15f
    return minOf(default.first, lowest) to maxOf(default.second, highest)
}

/** Channel-number ticks for the x axis, spaced so labels do not collide. */
private fun channelTicks(band: Band): List<Pair<Int, Int>> = when (band) {
    Band.GHZ_2_4 -> (1..13).map { it to 2407 + it * 5 }
    Band.GHZ_5 -> listOf(36, 44, 52, 60, 100, 108, 116, 124, 132, 140, 149, 157, 165)
        .map { it to 5000 + it * 5 }
    Band.GHZ_6 -> listOf(1, 21, 45, 69, 93, 117, 141, 165, 189, 213, 233)
        .map { it to 5950 + it * 5 }
    Band.UNKNOWN -> emptyList()
}

/** Plain-language advice about which 2.4 GHz channel is least crowded. */
private fun recommend24Channel(aps: List<AccessPoint>): String {
    val band24 = aps.filter { it.band == Band.GHZ_2_4 }
    if (band24.isEmpty()) return "No 2.4 GHz networks in range — nothing to avoid here."

    // Weight each candidate by the interference it would suffer: same channel
    // hurts most, and overlap falls off over the five adjacent channels.
    val scores = CLEAN_24_CHANNELS.associateWith { candidate ->
        band24.sumOf { ap ->
            val separation = kotlin.math.abs(ap.channel - candidate)
            val overlap = (5 - separation).coerceAtLeast(0) / 5.0
            overlap * signalWeight(ap.rssi)
        }
    }
    val best = scores.minByOrNull { it.value } ?: return "Use channel 1, 6 or 11."
    val worst = scores.maxByOrNull { it.value }

    val detail = scores.entries.sortedBy { it.key }
        .joinToString(", ") { "ch ${it.key}: ${"%.1f".format(it.value)}" }

    return if (worst != null && worst.value > 0 && best.value < worst.value * 0.8) {
        "Channel ${best.key} is the quietest of the three non-overlapping 2.4 GHz " +
            "channels right now (interference score — lower is better — $detail)."
    } else {
        "2.4 GHz is evenly congested ($detail). Moving to 5 GHz will help more than " +
            "changing channel."
    }
}

/** Strong neighbours interfere far more than distant ones. */
private fun signalWeight(rssi: Int): Double = when {
    rssi >= -50 -> 4.0
    rssi >= -60 -> 3.0
    rssi >= -70 -> 2.0
    rssi >= -80 -> 1.0
    else -> 0.4
}

/** Channel number for a tick frequency, kept for symmetry with the axis code. */
internal fun tickChannel(freq: Int): Int = channelForFrequency(freq)
