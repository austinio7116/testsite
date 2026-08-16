package com.wifisurvey.analyzer.survey

import org.json.JSONArray
import org.json.JSONObject

/**
 * One measurement taken at a known spot on the floor plan.
 *
 * [x] and [y] are metres from the top-left corner of the plan. Every AP
 * visible at that moment is recorded, so the same walk can be re-rendered
 * later for any network without re-surveying.
 */
data class SamplePoint(
    val id: Long,
    val x: Float,
    val y: Float,
    val takenAtMillis: Long,
    /** BSSID -> RSSI in dBm. */
    val readings: Map<String, Int>,
    /** BSSID -> SSID, kept so the heatmap can label networks after the fact. */
    val names: Map<String, String>
) {
    fun rssiFor(bssid: String): Int? = readings[bssid]

    /** Strongest reading across every AP broadcasting the given SSID. */
    fun bestRssiForSsid(ssid: String): Int? =
        readings.entries
            .filter { names[it.key] == ssid }
            .maxOfOrNull { it.value }

    /** Strongest reading of any network at all. */
    fun bestRssi(): Int? = readings.values.maxOrNull()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("t", takenAtMillis)
        put("r", JSONObject().also { obj -> readings.forEach { (k, v) -> obj.put(k, v) } })
        put("n", JSONObject().also { obj -> names.forEach { (k, v) -> obj.put(k, v) } })
    }

    companion object {
        fun fromJson(json: JSONObject): SamplePoint {
            val readings = mutableMapOf<String, Int>()
            json.optJSONObject("r")?.let { obj ->
                obj.keys().forEach { key -> readings[key] = obj.getInt(key) }
            }
            val names = mutableMapOf<String, String>()
            json.optJSONObject("n")?.let { obj ->
                obj.keys().forEach { key -> names[key] = obj.getString(key) }
            }
            return SamplePoint(
                id = json.optLong("id"),
                x = json.optDouble("x").toFloat(),
                y = json.optDouble("y").toFloat(),
                takenAtMillis = json.optLong("t"),
                readings = readings,
                names = names
            )
        }
    }
}

/** A straight wall segment drawn on the plan, in metres. */
data class Wall(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("x1", x1.toDouble()); put("y1", y1.toDouble())
        put("x2", x2.toDouble()); put("y2", y2.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject) = Wall(
            json.optDouble("x1").toFloat(), json.optDouble("y1").toFloat(),
            json.optDouble("x2").toFloat(), json.optDouble("y2").toFloat()
        )
    }
}

/** A whole room survey: plan dimensions, walls drawn, and every sample taken. */
data class Survey(
    val name: String = "Room survey",
    val widthMeters: Float = 6f,
    val heightMeters: Float = 5f,
    val samples: List<SamplePoint> = emptyList(),
    val walls: List<Wall> = emptyList(),
    val savedAtMillis: Long = 0L
) {
    /** Every BSSID seen anywhere in the survey, strongest-first. */
    fun observedBssids(): List<String> =
        samples.flatMap { it.readings.keys }.distinct().sortedByDescending { bssid ->
            samples.mapNotNull { it.rssiFor(bssid) }.maxOrNull() ?: Int.MIN_VALUE
        }

    fun observedSsids(): List<String> =
        samples.flatMap { it.names.values }.filter { it.isNotBlank() }.distinct().sorted()

    fun nameFor(bssid: String): String =
        samples.firstNotNullOfOrNull { it.names[bssid]?.takeIf { n -> n.isNotBlank() } } ?: bssid

    /** How many separate sample points contain a reading for this AP. */
    fun coverageCount(bssid: String): Int = samples.count { it.readings.containsKey(bssid) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("w", widthMeters.toDouble())
        put("h", heightMeters.toDouble())
        put("saved", savedAtMillis)
        put("samples", JSONArray().also { arr -> samples.forEach { arr.put(it.toJson()) } })
        put("walls", JSONArray().also { arr -> walls.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): Survey {
            val samples = mutableListOf<SamplePoint>()
            json.optJSONArray("samples")?.let { arr ->
                for (i in 0 until arr.length()) samples += SamplePoint.fromJson(arr.getJSONObject(i))
            }
            val walls = mutableListOf<Wall>()
            json.optJSONArray("walls")?.let { arr ->
                for (i in 0 until arr.length()) walls += Wall.fromJson(arr.getJSONObject(i))
            }
            return Survey(
                name = json.optString("name", "Room survey"),
                widthMeters = json.optDouble("w", 6.0).toFloat(),
                heightMeters = json.optDouble("h", 5.0).toFloat(),
                samples = samples,
                walls = walls,
                savedAtMillis = json.optLong("saved")
            )
        }
    }
}

/** What the heatmap should render. */
sealed interface HeatmapTarget {
    /** One specific radio. */
    data class SingleAp(val bssid: String) : HeatmapTarget

    /** Best signal across every radio broadcasting one SSID — a whole mesh. */
    data class Network(val ssid: String) : HeatmapTarget

    /** Best signal from anything at all. */
    data object BestAvailable : HeatmapTarget
}

/** Pull the RSSI a target sees at a given sample, if any. */
fun SamplePoint.rssiForTarget(target: HeatmapTarget): Int? = when (target) {
    is HeatmapTarget.SingleAp -> rssiFor(target.bssid)
    is HeatmapTarget.Network -> bestRssiForSsid(target.ssid)
    HeatmapTarget.BestAvailable -> bestRssi()
}
