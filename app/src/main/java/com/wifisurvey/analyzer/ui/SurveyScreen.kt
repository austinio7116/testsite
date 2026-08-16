package com.wifisurvey.analyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifisurvey.analyzer.survey.HeatmapEngine
import com.wifisurvey.analyzer.survey.HeatmapTarget
import kotlin.math.roundToInt

@Composable
fun SurveyScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val wifi by viewModel.wifiState.collectAsStateWithLifecycle()
    val walk by viewModel.walkState.collectAsStateWithLifecycle()
    val autoSample by viewModel.autoSample.collectAsStateWithLifecycle()

    var showRoomDialog by remember { mutableStateOf(false) }
    var showWalkSetup by remember { mutableStateOf(false) }

    val heatmap = remember(ui.field) {
        ui.field?.let { HeatmapEngine.render(it).asImageBitmap() }
    }
    val contours = remember(ui.field, ui.showContours) {
        val field = ui.field
        if (field != null && ui.showContours) {
            HeatmapEngine.contours(field, HeatmapEngine.defaultContourLevels)
        } else {
            emptyMap()
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {

        TargetSelector(viewModel, ui)

        Spacer(Modifier.height(8.dp))

        // A minimum height stops the surrounding controls from squeezing the
        // plan down to a sliver on shorter screens.
        Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 220.dp)) {
            FloorPlanView(
                survey = ui.survey,
                heatmap = heatmap,
                contours = contours,
                target = ui.target,
                walk = walk,
                showSamples = ui.showSamples,
                showContours = ui.showContours,
                pendingWallStart = ui.pendingWallStart,
                modifier = Modifier.fillMaxSize(),
                onTap = { x, y ->
                    if (ui.drawingWalls) {
                        viewModel.wallTap(x, y)
                    } else {
                        // Tapping during a walk both records a sample and
                        // re-anchors the drifting dead-reckoning cursor.
                        if (walk.running) viewModel.moveWalkCursor(x, y)
                        viewModel.addSampleAt(x, y)
                    }
                },
                onLongPress = { x, y -> viewModel.removeSampleNear(x, y, 0.6f) }
            )

            if (ui.survey.samples.isEmpty() && !ui.drawingWalls) {
                // Anchored to the bottom rather than centred: a centred card
                // covered the whole plan, so the room looked like it was not
                // being drawn at all.
                EmptyPlanHint(Modifier.align(Alignment.BottomCenter))
            }
        }

        Spacer(Modifier.height(8.dp))

        if (wifi.accessPoints.isEmpty()) {
            // With nothing detected the counters say nothing useful; show the
            // reason instead.
            DiagnosticsCard(wifi = wifi, onRescan = viewModel::forceScan)
        } else {
            ScanStatusRow(
                visibleAps = wifi.accessPoints.size,
                samples = ui.survey.samples.size,
                throttled = wifi.throttled,
                connectedRssi = wifi.connectedRssi,
                onRescan = viewModel::forceScan
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { showRoomDialog = true },
                label = { Text("${fmt(ui.survey.widthMeters)} × ${fmt(ui.survey.heightMeters)} m") }
            )
            FilterChip(
                selected = walk.running,
                onClick = {
                    if (walk.running) viewModel.stopWalk() else showWalkSetup = true
                },
                label = { Text(if (walk.running) "Stop walk" else "Walk mode") },
                leadingIcon = {
                    Icon(
                        if (walk.running) Icons.Filled.Stop else Icons.Filled.DirectionsWalk,
                        contentDescription = null,
                        Modifier.size(16.dp)
                    )
                }
            )
            if (walk.running) {
                AssistChip(
                    onClick = viewModel::calibrateHeading,
                    label = { Text("Set forward") },
                    leadingIcon = {
                        Icon(Icons.Filled.Explore, contentDescription = null, Modifier.size(16.dp))
                    }
                )
            }
            FilterChip(
                selected = ui.drawingWalls,
                onClick = viewModel::toggleWallDrawing,
                label = { Text(if (ui.drawingWalls) "Drawing walls" else "Draw walls") }
            )
            AssistChip(
                onClick = viewModel::addPerimeterWalls,
                label = { Text("Box the room") }
            )
            AssistChip(onClick = viewModel::clearWalls, label = { Text("Clear walls") })
            AssistChip(onClick = viewModel::clearSamples, label = { Text("Clear samples") })
        }

        Spacer(Modifier.height(8.dp))

        if (walk.running) {
            WalkStatusBar(
                steps = walk.steps,
                distance = walk.distanceMeters,
                autoSample = autoSample,
                onAutoSampleChange = viewModel::setAutoSample,
                onDropSample = { viewModel.addSampleAt(walk.x, walk.y) }
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showRoomDialog) {
        RoomSizeDialog(
            initialWidth = ui.survey.widthMeters,
            initialHeight = ui.survey.heightMeters,
            initialName = ui.survey.name,
            onDismiss = { showRoomDialog = false },
            onConfirm = { w, h, name ->
                viewModel.setRoomSize(w, h)
                viewModel.setSurveyName(name)
                showRoomDialog = false
            }
        )
    }

    if (showWalkSetup) {
        WalkSetupDialog(
            hasStepDetector = walk.hasStepDetector,
            hasCompass = walk.hasCompass,
            onDismiss = { showWalkSetup = false },
            onStart = { stride ->
                viewModel.setStride(stride)
                viewModel.startWalk(ui.survey.widthMeters / 2f, ui.survey.heightMeters - 0.3f)
                showWalkSetup = false
            }
        )
    }
}

@Composable
private fun TargetSelector(viewModel: AppViewModel, ui: SurveyUiState) {
    val ssids = remember(ui.survey.samples) { ui.survey.observedSsids() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = ui.target is HeatmapTarget.BestAvailable,
            onClick = { viewModel.setTarget(HeatmapTarget.BestAvailable) },
            label = { Text("Best signal") }
        )
        ssids.forEach { ssid ->
            FilterChip(
                selected = (ui.target as? HeatmapTarget.Network)?.ssid == ssid,
                onClick = { viewModel.setTarget(HeatmapTarget.Network(ssid)) },
                label = { Text(ssid, maxLines = 1) }
            )
        }
    }
}

@Composable
private fun ScanStatusRow(
    visibleAps: Int,
    samples: Int,
    throttled: Boolean,
    connectedRssi: Int?,
    onRescan: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatChip("Samples", samples.toString(), Modifier.weight(1f))
        StatChip("Networks", visibleAps.toString(), Modifier.weight(1f))
        StatChip(
            "Connected",
            connectedRssi?.let { "$it dBm" } ?: "—",
            Modifier.weight(1.2f),
            accent = connectedRssi?.let { SignalColors.forDbm(it) }
                ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onRescan, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Rescan", Modifier.size(18.dp))
        }
    }
    if (throttled) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Android is throttling Wi-Fi scans (4 per 2 min). Readings still update from " +
                "the system's own scans — turn off \"Wi-Fi scan throttling\" in Developer " +
                "options for faster surveying.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WalkStatusBar(
    steps: Int,
    distance: Float,
    autoSample: Boolean,
    onAutoSampleChange: (Boolean) -> Unit,
    onDropSample: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$steps steps · ${fmt(distance)} m walked", fontWeight = FontWeight.Medium)
                Text(
                    "Hold the phone flat, facing the way you walk",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Auto", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = autoSample, onCheckedChange = onAutoSampleChange)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDropSample) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sample")
            }
        }
    }
}

@Composable
private fun EmptyPlanHint(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            "Stand somewhere in the room, then tap that spot on the plan above. " +
                "Repeat around the room — the heatmap builds as you go. " +
                "Long-press a dot to delete it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RoomSizeDialog(
    initialWidth: Float,
    initialHeight: Float,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (Float, Float, String) -> Unit
) {
    var width by remember { mutableStateOf(fmt(initialWidth)) }
    var height by remember { mutableStateOf(fmt(initialHeight)) }
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it },
                        label = { Text("Width (m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it },
                        label = { Text("Depth (m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pace it out if you have to — a stride is roughly 0.75 m. " +
                        "Accuracy here sets the scale for every distance the app reports.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = width.toFloatOrNull() ?: initialWidth
                val h = height.toFloatOrNull() ?: initialHeight
                onConfirm(w, h, name.ifBlank { "Room survey" })
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun WalkSetupDialog(
    hasStepDetector: Boolean,
    hasCompass: Boolean,
    onDismiss: () -> Unit,
    onStart: (Float) -> Unit
) {
    var stride by remember { mutableStateOf(0.72f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Walk mode") },
        text = {
            Column {
                Text(
                    "The cursor starts at the bottom-centre of the plan and advances one " +
                        "stride per detected step, in the direction you are facing. " +
                        "Point the phone the way you want \"up\" on the plan before starting.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                Text("Stride length: ${fmt(stride)} m", fontWeight = FontWeight.Medium)
                Slider(
                    value = stride,
                    onValueChange = { stride = (it * 100).roundToInt() / 100f },
                    valueRange = 0.4f..1.0f
                )
                if (!hasStepDetector) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This device has no step detector, so the cursor will not advance " +
                            "on its own. Tap the plan to place samples instead.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (!hasCompass) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No compass detected — heading will not track turns.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dead reckoning drifts. Re-tap the plan any time to correct the cursor.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onStart(stride) }) { Text("Start walking") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

internal fun fmt(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString()
    else String.format("%.1f", value)
