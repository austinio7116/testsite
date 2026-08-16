package com.wifisurvey.analyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifisurvey.analyzer.export.Exporter
import com.wifisurvey.analyzer.survey.HeatmapTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CoverageScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard("Coverage — ${viewModel.targetLabel()}") {
                val stats = ui.stats
                if (stats == null) {
                    Text(
                        "Take a few samples on the Survey tab and the numbers appear here.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip("Strongest", "${stats.measuredMax} dBm", Modifier.weight(1f))
                        StatChip("Weakest", "${stats.measuredMin} dBm", Modifier.weight(1f))
                        StatChip(
                            "Mean",
                            "${stats.measuredMean.roundToInt()} dBm",
                            Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    CoverageBar("Video-call grade (≥ −67 dBm)", stats.fractionExcellent, Color67)
                    Spacer(Modifier.height(6.dp))
                    CoverageBar("Usable (≥ −75 dBm)", stats.fractionUsable, Color75)
                    Spacer(Modifier.height(6.dp))
                    CoverageBar("Dead zone (< −80 dBm)", stats.fractionDead, Color80)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${stats.sampleCount} samples cover " +
                            "${(stats.fractionMapped * 100).roundToInt()}% of the floor area. " +
                            "Walk into the unmapped parts and tap to fill them in.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionCard("Legend") { HeatmapLegend() }
        }

        item {
            SectionCard("Which network") {
                Text(
                    "Pick a single radio to see one access point's reach, or a network " +
                        "name to see the whole mesh combined.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                val bssids = remember(ui.survey.samples) { ui.survey.observedBssids() }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = ui.target is HeatmapTarget.BestAvailable,
                        onClick = { viewModel.setTarget(HeatmapTarget.BestAvailable) },
                        label = { Text("Best available signal") }
                    )
                    ui.survey.observedSsids().forEach { ssid ->
                        FilterChip(
                            selected = (ui.target as? HeatmapTarget.Network)?.ssid == ssid,
                            onClick = { viewModel.setTarget(HeatmapTarget.Network(ssid)) },
                            label = { Text("Network: $ssid") }
                        )
                    }
                    bssids.take(14).forEach { bssid ->
                        val name = ui.survey.nameFor(bssid)
                        FilterChip(
                            selected = (ui.target as? HeatmapTarget.SingleAp)?.bssid == bssid,
                            onClick = { viewModel.setTarget(HeatmapTarget.SingleAp(bssid)) },
                            label = {
                                Text(
                                    "$name · ${bssid.takeLast(8)} " +
                                        "(${ui.survey.coverageCount(bssid)} pts)",
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }
        }

        item {
            SectionCard("Rendering") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Smoothing radius", Modifier.weight(1f), fontSize = 13.sp)
                    Text("${fmt(ui.influenceRadius)} m", fontWeight = FontWeight.Medium)
                }
                Slider(
                    value = ui.influenceRadius,
                    onValueChange = { viewModel.setInfluenceRadius(it) },
                    valueRange = 1f..10f
                )
                Text(
                    "How far a single reading is allowed to speak for. Small values give a " +
                        "spotty but honest map; large values fill the room with guesswork.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = ui.showContours,
                        onClick = viewModel::toggleContours,
                        label = { Text("Contours") }
                    )
                    FilterChip(
                        selected = ui.showSamples,
                        onClick = viewModel::toggleSampleMarkers,
                        label = { Text("Sample dots") }
                    )
                }
            }
        }

        item {
            SectionCard("Export") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val bitmap = viewModel.renderBitmap()
                            if (bitmap != null) {
                                val file = Exporter.writePng(context, bitmap, viewModel.targetLabel())
                                Exporter.share(context, file, "image/png", "Wi-Fi heatmap")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("PNG") }
                    OutlinedButton(
                        onClick = {
                            val file = Exporter.writeCsv(context, ui.survey)
                            Exporter.share(context, file, "text/csv", "Wi-Fi survey data")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("CSV") }
                    OutlinedButton(
                        onClick = {
                            val file = Exporter.writeJson(context, ui.survey)
                            Exporter.share(context, file, "application/json", "Wi-Fi survey")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("JSON") }
                }
            }
        }

        item {
            SectionCard("Surveys") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::saveSurvey, modifier = Modifier.weight(1f)) {
                        Text("Save")
                    }
                    OutlinedButton(onClick = viewModel::newSurvey, modifier = Modifier.weight(1f)) {
                        Text("New")
                    }
                }
                if (ui.savedSurveys.isNotEmpty()) Spacer(Modifier.height(10.dp))
            }
        }

        items(ui.savedSurveys, key = { it.fileName }) { saved ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { viewModel.loadSurvey(saved.fileName) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(saved.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${saved.samples} samples · ${formatStamp(saved.savedAtMillis)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.deleteSurvey(saved.fileName) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete survey",
                        Modifier.size(18.dp)
                    )
                }
            }
        }

        item {
            SectionCard("Reading the map") {
                Text(
                    "• −30 to −55 dBm: full speed, right next to the router.\n" +
                        "• −67 dBm: the planning floor for video calls and HD streaming.\n" +
                        "• −70 to −80 dBm: browsing works, throughput drops sharply.\n" +
                        "• Below −85 dBm: the client will roam away or drop.\n\n" +
                        "Signal halves in power every 3 dB, so a 10 dB dip across a doorway " +
                        "is a real wall problem, not noise.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun CoverageBar(label: String, fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), fontSize = 12.sp)
            Text(
                "${(fraction * 100).roundToInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

private val Color67 = androidx.compose.ui.graphics.Color(0xFF48D17A)
private val Color75 = androidx.compose.ui.graphics.Color(0xFFF2B134)
private val Color80 = androidx.compose.ui.graphics.Color(0xFFE8622A)

private fun formatStamp(millis: Long): String =
    if (millis <= 0) "unsaved"
    else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
