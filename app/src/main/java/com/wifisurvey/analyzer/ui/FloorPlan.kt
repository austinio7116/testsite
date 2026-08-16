package com.wifisurvey.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.wifisurvey.analyzer.survey.HeatmapEngine
import com.wifisurvey.analyzer.survey.HeatmapTarget
import com.wifisurvey.analyzer.survey.SamplePoint
import com.wifisurvey.analyzer.survey.Survey
import com.wifisurvey.analyzer.survey.WalkState
import com.wifisurvey.analyzer.survey.rssiForTarget
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Top-down view of the room: grid, walls, interpolated heatmap, iso-contours,
 * every sample point, and the live walk cursor.
 *
 * Taps are reported in metres from the plan's top-left corner.
 */
@Composable
fun FloorPlanView(
    survey: Survey,
    heatmap: ImageBitmap?,
    contours: Map<Float, List<FloatArray>>,
    target: HeatmapTarget,
    walk: WalkState?,
    showSamples: Boolean,
    showContours: Boolean,
    pendingWallStart: Pair<Float, Float>?,
    modifier: Modifier = Modifier,
    onTap: ((Float, Float) -> Unit)? = null,
    onLongPress: ((Float, Float) -> Unit)? = null
) {
    val measurer = rememberTextMeasurer()
    val gridColor = Color.White.copy(alpha = 0.07f)
    val strongGrid = Color.White.copy(alpha = 0.16f)
    val wallColor = Color(0xFFCBD3EA)
    val labelColor = Color(0xFF8E98B8)

    // Recomputed only when the room actually changes shape.
    val geometry = remember(survey.widthMeters, survey.heightMeters) {
        survey.widthMeters to survey.heightMeters
    }

    Canvas(
        modifier = modifier.pointerInput(geometry, onTap, onLongPress) {
            detectTapGestures(
                onTap = { offset ->
                    val rect = planRect(size.width.toFloat(), size.height.toFloat(), geometry.first, geometry.second)
                    toMeters(offset, rect, geometry.first, geometry.second)?.let { (mx, my) ->
                        onTap?.invoke(mx, my)
                    }
                },
                onLongPress = { offset ->
                    val rect = planRect(size.width.toFloat(), size.height.toFloat(), geometry.first, geometry.second)
                    toMeters(offset, rect, geometry.first, geometry.second)?.let { (mx, my) ->
                        onLongPress?.invoke(mx, my)
                    }
                }
            )
        }
    ) {
        val widthM = survey.widthMeters
        val heightM = survey.heightMeters
        val rect = planRect(size.width, size.height, widthM, heightM)
        val pxPerMeter = rect.width / widthM

        fun px(x: Float, y: Float) = Offset(rect.left + x * pxPerMeter, rect.top + y * pxPerMeter)

        // Floor
        drawRect(
            color = Color(0xFF0E1428),
            topLeft = rect.topLeft,
            size = Size(rect.width, rect.height)
        )

        // Heatmap, stretched over the whole plan.
        heatmap?.let { image ->
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
                filterQuality = FilterQuality.High
            )
        }

        // 1 m grid, every 5th line brighter.
        val gridStep = gridStepFor(widthM, heightM)
        var gx = 0f
        while (gx <= widthM + 0.001f) {
            val strong = (gx / gridStep).toInt() % 5 == 0
            drawLine(
                color = if (strong) strongGrid else gridColor,
                start = px(gx, 0f),
                end = px(gx, heightM),
                strokeWidth = if (strong) 1.4f else 1f
            )
            gx += gridStep
        }
        var gy = 0f
        while (gy <= heightM + 0.001f) {
            val strong = (gy / gridStep).toInt() % 5 == 0
            drawLine(
                color = if (strong) strongGrid else gridColor,
                start = px(0f, gy),
                end = px(widthM, gy),
                strokeWidth = if (strong) 1.4f else 1f
            )
            gy += gridStep
        }

        // Iso-contours at the planning thresholds.
        if (showContours) {
            contours.forEach { (level, segments) ->
                val color = Color(HeatmapEngine.colorForDbm(level))
                val emphasis = if (level == -67f) 2.4f else 1.4f
                segments.forEach { seg ->
                    drawLine(
                        color = color.copy(alpha = 0.9f),
                        start = px(seg[0], seg[1]),
                        end = px(seg[2], seg[3]),
                        strokeWidth = emphasis,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // Walls
        survey.walls.forEach { wall ->
            drawLine(
                color = wallColor,
                start = px(wall.x1, wall.y1),
                end = px(wall.x2, wall.y2),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }

        // Wall being drawn: show the anchored end.
        pendingWallStart?.let { (sx, sy) ->
            drawCircle(color = Color(0xFFF2B134), radius = 7f, center = px(sx, sy))
        }

        // Plan border
        drawRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = rect.topLeft,
            size = Size(rect.width, rect.height),
            style = Stroke(width = 2f)
        )

        // Sample points, tinted by what this AP measured there.
        if (showSamples) {
            survey.samples.forEach { sample ->
                drawSampleMarker(sample, target, px(sample.x, sample.y))
            }
        }

        // Live walk cursor with a heading arrow.
        if (walk != null && walk.running) {
            val centre = px(walk.x, walk.y)
            drawCircle(color = Color(0xFF35E0D8).copy(alpha = 0.18f), radius = 26f, center = centre)
            drawCircle(color = Color(0xFF35E0D8), radius = 8f, center = centre)
            drawCircle(color = Color(0xFF06121F), radius = 3.5f, center = centre)
            val tip = Offset(
                centre.x + 26f * sin(walk.headingRad),
                centre.y - 26f * cos(walk.headingRad)
            )
            drawLine(
                color = Color(0xFF35E0D8),
                start = centre,
                end = tip,
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }

        // Axis labels in metres.
        drawRuler(measurer, rect, widthM, heightM, gridStep, labelColor)

        // Scale bar
        drawScaleBar(measurer, rect, pxPerMeter, gridStep, labelColor)
    }
}

private fun DrawScope.drawSampleMarker(
    sample: SamplePoint,
    target: HeatmapTarget,
    centre: Offset
) {
    val rssi = sample.rssiForTarget(target)
    if (rssi == null) {
        // Sampled here, but this AP was not audible — worth showing as a hole.
        drawCircle(
            color = Color.White.copy(alpha = 0.45f),
            radius = 5f,
            center = centre,
            style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
        )
        return
    }
    val color = Color(HeatmapEngine.colorForDbm(rssi.toFloat()))
    drawCircle(color = Color(0xFF06121F), radius = 6.5f, center = centre)
    drawCircle(color = color, radius = 4.5f, center = centre)
}

private fun DrawScope.drawRuler(
    measurer: TextMeasurer,
    rect: Rect,
    widthM: Float,
    heightM: Float,
    step: Float,
    color: Color
) {
    val style = TextStyle(fontSize = 9.sp, color = color)
    val labelStep = step * 5
    var x = 0f
    while (x <= widthM + 0.001f) {
        val text = formatMeters(x)
        val layout = measurer.measure(text, style)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                rect.left + (x / widthM) * rect.width - layout.size.width / 2f,
                rect.bottom + 4f
            )
        )
        x += labelStep
    }
    var y = 0f
    while (y <= heightM + 0.001f) {
        val text = formatMeters(y)
        val layout = measurer.measure(text, style)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                rect.left - layout.size.width - 6f,
                rect.top + (y / heightM) * rect.height - layout.size.height / 2f
            )
        )
        y += labelStep
    }
}

private fun DrawScope.drawScaleBar(
    measurer: TextMeasurer,
    rect: Rect,
    pxPerMeter: Float,
    step: Float,
    color: Color
) {
    val barMeters = step * 5
    val barPx = barMeters * pxPerMeter
    if (barPx > rect.width * 0.6f || barPx < 12f) return
    val y = rect.bottom - 14f
    val left = rect.right - barPx - 12f
    drawLine(color = color, start = Offset(left, y), end = Offset(left + barPx, y), strokeWidth = 2f)
    drawLine(color = color, start = Offset(left, y - 4f), end = Offset(left, y + 4f), strokeWidth = 2f)
    drawLine(
        color = color,
        start = Offset(left + barPx, y - 4f),
        end = Offset(left + barPx, y + 4f),
        strokeWidth = 2f
    )
    val layout = measurer.measure(formatMeters(barMeters) + " m", TextStyle(fontSize = 9.sp, color = color))
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(left + barPx / 2f - layout.size.width / 2f, y - layout.size.height - 4f)
    )
}

private fun formatMeters(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else String.format("%.1f", value)

/** Grid spacing that keeps the plan readable regardless of room size. */
private fun gridStepFor(widthM: Float, heightM: Float): Float {
    val longest = maxOf(widthM, heightM)
    return when {
        longest <= 8f -> 0.5f
        longest <= 20f -> 1f
        longest <= 40f -> 2f
        else -> 5f
    }
}

/** The aspect-correct rectangle the plan occupies, leaving room for labels. */
private fun planRect(canvasWidth: Float, canvasHeight: Float, widthM: Float, heightM: Float): Rect {
    val padLeft = 26f
    val padRight = 8f
    val padTop = 8f
    val padBottom = 20f
    val availableW = (canvasWidth - padLeft - padRight).coerceAtLeast(1f)
    val availableH = (canvasHeight - padTop - padBottom).coerceAtLeast(1f)
    val scale = min(availableW / widthM, availableH / heightM)
    val w = widthM * scale
    val h = heightM * scale
    val left = padLeft + (availableW - w) / 2f
    val top = padTop + (availableH - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** Screen offset to plan metres; null when the tap landed outside the room. */
private fun toMeters(offset: Offset, rect: Rect, widthM: Float, heightM: Float): Pair<Float, Float>? {
    if (rect.width <= 0f || rect.height <= 0f) return null
    val mx = (offset.x - rect.left) / rect.width * widthM
    val my = (offset.y - rect.top) / rect.height * heightM
    val slackX = widthM * 0.04f
    val slackY = heightM * 0.04f
    if (mx < -slackX || my < -slackY || mx > widthM + slackX || my > heightM + slackY) return null
    return mx.coerceIn(0f, widthM) to my.coerceIn(0f, heightM)
}

/** Number of grid columns, exposed for tests and tooling. */
internal fun gridColumns(widthM: Float): Int = ceil(widthM / gridStepFor(widthM, widthM)).toInt()
