package com.wifisurvey.analyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifisurvey.analyzer.survey.HeatmapEngine
import com.wifisurvey.analyzer.wifi.SignalScale

/** Four-bar signal indicator, coloured by strength. */
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier, barWidth: Int = 4) {
    val filled = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
    val color = SignalColors.forDbm(rssi)
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        repeat(4) { index ->
            Box(
                Modifier
                    .padding(end = 2.dp)
                    .width(barWidth.dp)
                    .height((6 + index * 4).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (index < filled) color else Color.White.copy(alpha = 0.14f))
            )
        }
    }
}

/** Small labelled value used across the stats rows. */
@Composable
fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = accent)
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

/** Horizontal colour ramp with dBm ticks, matching the heatmap palette. */
@Composable
fun HeatmapLegend(modifier: Modifier = Modifier) {
    val stops = listOf(-95f, -85f, -75f, -67f, -60f, -50f, -40f, -30f)
    val colors = stops.map { Color(HeatmapEngine.colorForDbm(it)) }
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.horizontalGradient(colors))
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("−95", "−75", "−67", "−55", "−30").forEach {
                Text(
                    "$it dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Dead",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "−67 dBm = video-call grade",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Excellent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Rolling RSSI plot for one access point. */
@Composable
fun RssiSparkline(
    history: List<Int>,
    modifier: Modifier = Modifier,
    minDbm: Float = SignalScale.MIN_DBM,
    maxDbm: Float = SignalScale.MAX_DBM
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // Reference lines at the thresholds that matter.
        listOf(-50f, -67f, -80f).forEach { level ->
            val t = ((level - minDbm) / (maxDbm - minDbm)).coerceIn(0f, 1f)
            val y = h - t * h
            drawLine(
                color = outline,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f
            )
        }

        if (history.size < 2) return@Canvas

        val stepX = w / (history.size - 1).toFloat()
        val path = Path()
        val fill = Path()
        history.forEachIndexed { index, rssi ->
            val t = ((rssi - minDbm) / (maxDbm - minDbm)).coerceIn(0f, 1f)
            val x = index * stepX
            val y = h - t * h
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(w, h)
        fill.close()

        val latest = history.last()
        val lineColor = SignalColors.forDbm(latest)
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0.02f))
            )
        )
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )
        val lastT = ((latest - minDbm) / (maxDbm - minDbm)).coerceIn(0f, 1f)
        drawCircle(
            color = lineColor,
            radius = 4f,
            center = Offset(w, h - lastT * h)
        )
        drawLine(color = labelColor.copy(alpha = 0.2f), start = Offset(0f, h), end = Offset(w, h))
    }
}

/** Compact horizontal strength meter used in the AP list. */
@Composable
fun StrengthMeter(rssi: Int, modifier: Modifier = Modifier) {
    val fraction = SignalScale.normalize(rssi)
    val color = SignalColors.forDbm(rssi)
    Box(
        modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp))
            .background(color)
    )
}
