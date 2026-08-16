package com.wifisurvey.analyzer.survey

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** A sample reduced to what the interpolator needs. */
data class FieldSample(val x: Float, val y: Float, val dbm: Float)

/**
 * A regular grid of interpolated signal values.
 *
 * [values] holds dBm per cell, or [Float.NaN] where no sample was close
 * enough to say anything honest. [confidence] is 0..1 and falls off with
 * distance from the nearest real measurement.
 */
class HeatmapField(
    val cols: Int,
    val rows: Int,
    val widthMeters: Float,
    val heightMeters: Float,
    val values: FloatArray,
    val confidence: FloatArray
) {
    fun valueAt(col: Int, row: Int): Float = values[row * cols + col]

    fun sampleAtMeters(x: Float, y: Float): Float {
        if (widthMeters <= 0f || heightMeters <= 0f) return Float.NaN
        val col = ((x / widthMeters) * cols).toInt().coerceIn(0, cols - 1)
        val row = ((y / heightMeters) * rows).toInt().coerceIn(0, rows - 1)
        return valueAt(col, row)
    }
}

/** Aggregate numbers describing how good the coverage actually is. */
data class CoverageStats(
    val measuredMin: Int,
    val measuredMax: Int,
    val measuredMean: Float,
    val sampleCount: Int,
    /** Fraction of the mapped area at or above −67 dBm (voice/video grade). */
    val fractionExcellent: Float,
    /** Fraction at or above −75 dBm (usable). */
    val fractionUsable: Float,
    /** Fraction below −80 dBm — the dead zones. */
    val fractionDead: Float,
    /** Fraction of the room that has any interpolated value at all. */
    val fractionMapped: Float
)

object HeatmapEngine {

    /** Target long-edge resolution of the interpolation grid. */
    private const val GRID_LONG_EDGE = 150

    /**
     * Inverse-distance weighting with a bounded search radius.
     *
     * Power 2.4 was chosen empirically: lower values smear a strong reading
     * across the whole room, higher ones produce bullseyes around each sample.
     * Cells further than [influenceRadiusMeters] from every sample stay NaN so
     * the UI can show unmapped floor rather than invented data.
     */
    fun interpolate(
        samples: List<FieldSample>,
        widthMeters: Float,
        heightMeters: Float,
        influenceRadiusMeters: Float = 3.5f,
        power: Float = 2.4f,
        smoothingPasses: Int = 1
    ): HeatmapField? {
        if (samples.isEmpty() || widthMeters <= 0f || heightMeters <= 0f) return null

        val aspect = widthMeters / heightMeters
        val cols: Int
        val rows: Int
        if (aspect >= 1f) {
            cols = GRID_LONG_EDGE
            rows = max(8, (GRID_LONG_EDGE / aspect).toInt())
        } else {
            rows = GRID_LONG_EDGE
            cols = max(8, (GRID_LONG_EDGE * aspect).toInt())
        }

        val values = FloatArray(cols * rows) { Float.NaN }
        val confidence = FloatArray(cols * rows)
        val cellW = widthMeters / cols
        val cellH = heightMeters / rows

        for (row in 0 until rows) {
            val py = (row + 0.5f) * cellH
            for (col in 0 until cols) {
                val px = (col + 0.5f) * cellW
                var weightSum = 0.0
                var valueSum = 0.0
                var nearest = Float.MAX_VALUE

                for (s in samples) {
                    val dx = px - s.x
                    val dy = py - s.y
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < nearest) nearest = dist
                    if (dist > influenceRadiusMeters) continue
                    if (dist < 1e-4f) {
                        // Standing exactly on a sample: take it verbatim.
                        weightSum = 1.0
                        valueSum = s.dbm.toDouble()
                        break
                    }
                    val w = 1.0 / dist.toDouble().pow(power.toDouble())
                    weightSum += w
                    valueSum += w * s.dbm
                }

                val index = row * cols + col
                if (weightSum > 0.0) {
                    values[index] = (valueSum / weightSum).toFloat()
                    // Confidence decays over the outer third of the radius.
                    val fade = 1f - ((nearest / influenceRadiusMeters - 0.66f) / 0.34f)
                    confidence[index] = fade.coerceIn(0.25f, 1f)
                }
            }
        }

        repeat(smoothingPasses) { smooth(values, cols, rows) }
        return HeatmapField(cols, rows, widthMeters, heightMeters, values, confidence)
    }

    /** 3x3 mean filter that ignores unmapped neighbours. */
    private fun smooth(values: FloatArray, cols: Int, rows: Int) {
        val copy = values.copyOf()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val index = row * cols + col
                if (copy[index].isNaN()) continue
                var sum = 0f
                var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = col + dx
                        val ny = row + dy
                        if (nx !in 0 until cols || ny !in 0 until rows) continue
                        val v = copy[ny * cols + nx]
                        if (v.isNaN()) continue
                        sum += v
                        count++
                    }
                }
                if (count > 0) values[index] = sum / count
            }
        }
    }

    /** Render the field to a bitmap, one pixel per grid cell. */
    fun render(field: HeatmapField, fadeLowConfidence: Boolean = true): Bitmap {
        val pixels = IntArray(field.cols * field.rows)
        for (i in pixels.indices) {
            val v = field.values[i]
            pixels[i] = if (v.isNaN()) {
                Color.TRANSPARENT
            } else {
                val base = colorForDbm(v)
                val alpha = if (fadeLowConfidence) {
                    (255 * (0.45f + 0.55f * field.confidence[i])).toInt().coerceIn(0, 255)
                } else {
                    255
                }
                Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
            }
        }
        val bitmap = Bitmap.createBitmap(field.cols, field.rows, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, field.cols, 0, 0, field.cols, field.rows)
        return bitmap
    }

    fun stats(field: HeatmapField?, samples: List<FieldSample>): CoverageStats? {
        if (samples.isEmpty()) return null
        val measured = samples.map { it.dbm }
        var mapped = 0
        var excellent = 0
        var usable = 0
        var dead = 0
        field?.values?.forEach { v ->
            if (v.isNaN()) return@forEach
            mapped++
            if (v >= -67f) excellent++
            if (v >= -75f) usable++
            if (v < -80f) dead++
        }
        val total = field?.values?.size ?: 0
        return CoverageStats(
            measuredMin = measured.min().toInt(),
            measuredMax = measured.max().toInt(),
            measuredMean = measured.average().toFloat(),
            sampleCount = samples.size,
            fractionExcellent = if (mapped > 0) excellent.toFloat() / mapped else 0f,
            fractionUsable = if (mapped > 0) usable.toFloat() / mapped else 0f,
            fractionDead = if (mapped > 0) dead.toFloat() / mapped else 0f,
            fractionMapped = if (total > 0) mapped.toFloat() / total else 0f
        )
    }

    /**
     * Marching squares over the field, returning iso-line segments in metres
     * for each requested dBm level. Segments are in plan coordinates.
     */
    fun contours(field: HeatmapField, levels: List<Float>): Map<Float, List<FloatArray>> {
        val result = mutableMapOf<Float, List<FloatArray>>()
        val cellW = field.widthMeters / field.cols
        val cellH = field.heightMeters / field.rows

        for (level in levels) {
            val segments = mutableListOf<FloatArray>()
            for (row in 0 until field.rows - 1) {
                for (col in 0 until field.cols - 1) {
                    val tl = field.valueAt(col, row)
                    val tr = field.valueAt(col + 1, row)
                    val br = field.valueAt(col + 1, row + 1)
                    val bl = field.valueAt(col, row + 1)
                    if (tl.isNaN() || tr.isNaN() || br.isNaN() || bl.isNaN()) continue

                    var index = 0
                    if (tl >= level) index = index or 8
                    if (tr >= level) index = index or 4
                    if (br >= level) index = index or 2
                    if (bl >= level) index = index or 1
                    if (index == 0 || index == 15) continue

                    val x0 = (col + 0.5f) * cellW
                    val y0 = (row + 0.5f) * cellH
                    val x1 = (col + 1.5f) * cellW
                    val y1 = (row + 1.5f) * cellH

                    fun lerpX(a: Float, b: Float) = x0 + (x1 - x0) * interp(a, b, level)
                    fun lerpY(a: Float, b: Float) = y0 + (y1 - y0) * interp(a, b, level)

                    val top = floatArrayOf(lerpX(tl, tr), y0)
                    val bottom = floatArrayOf(lerpX(bl, br), y1)
                    val left = floatArrayOf(x0, lerpY(tl, bl))
                    val right = floatArrayOf(x1, lerpY(tr, br))

                    fun add(a: FloatArray, b: FloatArray) {
                        segments += floatArrayOf(a[0], a[1], b[0], b[1])
                    }

                    when (index) {
                        1, 14 -> add(left, bottom)
                        2, 13 -> add(bottom, right)
                        3, 12 -> add(left, right)
                        4, 11 -> add(top, right)
                        6, 9 -> add(top, bottom)
                        7, 8 -> add(left, top)
                        5 -> { add(left, top); add(bottom, right) }
                        10 -> { add(left, bottom); add(top, right) }
                    }
                }
            }
            result[level] = segments
        }
        return result
    }

    private fun interp(a: Float, b: Float, level: Float): Float {
        val denominator = b - a
        if (abs(denominator) < 1e-6f) return 0.5f
        return ((level - a) / denominator).coerceIn(0f, 1f)
    }

    /** Colour stops from unusable (deep violet) to excellent (teal). */
    private val stops = listOf(
        -100f to Color.rgb(0x3B, 0x0F, 0x70),
        -90f to Color.rgb(0x7B, 0x23, 0x82),
        -82f to Color.rgb(0xC1, 0x3B, 0x60),
        -75f to Color.rgb(0xE8, 0x62, 0x2A),
        -70f to Color.rgb(0xF0, 0x8C, 0x21),
        -67f to Color.rgb(0xF2, 0xB1, 0x34),
        -60f to Color.rgb(0xC8, 0xCB, 0x3A),
        -55f to Color.rgb(0x8F, 0xD1, 0x4F),
        -48f to Color.rgb(0x48, 0xD1, 0x7A),
        -40f to Color.rgb(0x21, 0xC7, 0xA8),
        -30f to Color.rgb(0x35, 0xE0, 0xD8)
    )

    /** Interpolated colour for a dBm value, as a packed ARGB int. */
    fun colorForDbm(dbm: Float): Int {
        val v = dbm.coerceIn(stops.first().first, stops.last().first)
        for (i in 0 until stops.size - 1) {
            val (lowDbm, lowColor) = stops[i]
            val (highDbm, highColor) = stops[i + 1]
            if (v in lowDbm..highDbm) {
                val t = if (highDbm == lowDbm) 0f else (v - lowDbm) / (highDbm - lowDbm)
                return blend(lowColor, highColor, t)
            }
        }
        return stops.last().second
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int) = (x + (y - x) * clamped).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(a), Color.red(b)),
            mix(Color.green(a), Color.green(b)),
            mix(Color.blue(a), Color.blue(b))
        )
    }

    /** Evenly spaced legend entries for the UI. */
    fun legendStops(): List<Pair<Float, Int>> =
        listOf(-90f, -80f, -70f, -60f, -50f, -40f).map { it to colorForDbm(it) }

    /** Contour levels worth drawing — the planning thresholds people care about. */
    val defaultContourLevels = listOf(-80f, -70f, -67f, -55f)

    fun clampDbm(value: Float): Float = min(max(value, -100f), -20f)
}
