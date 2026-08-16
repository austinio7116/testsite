package com.wifisurvey.analyzer.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifisurvey.analyzer.survey.CoverageStats
import com.wifisurvey.analyzer.survey.FieldSample
import com.wifisurvey.analyzer.survey.HeatmapEngine
import com.wifisurvey.analyzer.survey.HeatmapField
import com.wifisurvey.analyzer.survey.HeatmapTarget
import com.wifisurvey.analyzer.survey.SamplePoint
import com.wifisurvey.analyzer.survey.SavedSurvey
import com.wifisurvey.analyzer.survey.Survey
import com.wifisurvey.analyzer.survey.SurveyStore
import com.wifisurvey.analyzer.survey.WalkState
import com.wifisurvey.analyzer.survey.WalkTracker
import com.wifisurvey.analyzer.survey.Wall
import com.wifisurvey.analyzer.survey.rssiForTarget
import com.wifisurvey.analyzer.wifi.AccessPoint
import com.wifisurvey.analyzer.wifi.WifiScanner
import com.wifisurvey.analyzer.wifi.WifiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the survey screens read from. */
data class SurveyUiState(
    val survey: Survey = Survey(),
    val target: HeatmapTarget = HeatmapTarget.BestAvailable,
    val field: HeatmapField? = null,
    val stats: CoverageStats? = null,
    val computing: Boolean = false,
    val showContours: Boolean = true,
    val showSamples: Boolean = true,
    val influenceRadius: Float = 3.5f,
    val drawingWalls: Boolean = false,
    val pendingWallStart: Pair<Float, Float>? = null,
    val savedSurveys: List<SavedSurvey> = emptyList(),
    val message: String? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = WifiScanner(app)
    private val store = SurveyStore(app)
    val walkTracker = WalkTracker(app)

    val wifiState: StateFlow<WifiState> = scanner.state
    val walkState: StateFlow<WalkState> = walkTracker.state

    private val _ui = MutableStateFlow(SurveyUiState())
    val ui: StateFlow<SurveyUiState> = _ui.asStateFlow()

    /** RSSI history for the AP shown in the live graph, oldest first. */
    private val _history = MutableStateFlow<List<Int>>(emptyList())
    val history: StateFlow<List<Int>> = _history.asStateFlow()

    private val _trackedBssid = MutableStateFlow<String?>(null)
    val trackedBssid: StateFlow<String?> = _trackedBssid.asStateFlow()

    /** Auto-drop a sample on every detected step while walking. */
    private val _autoSample = MutableStateFlow(true)
    val autoSample: StateFlow<Boolean> = _autoSample.asStateFlow()

    private var computeJob: Job? = null
    private var nextSampleId = 1L

    init {
        scanner.start(viewModelScope)
        walkTracker.onStep = { x, y ->
            if (_autoSample.value) addSampleAt(x, y)
        }
        viewModelScope.launch {
            scanner.state.collect { state -> recordHistory(state) }
        }
        refreshSavedList()
    }

    override fun onCleared() {
        scanner.stop()
        walkTracker.stop()
        super.onCleared()
    }

    // ---------------------------------------------------------------- scanning

    fun forceScan() {
        scanner.requestScanIfAllowed(force = true)
        // Re-read the cache straight away so the button gives feedback now
        // rather than at the next poll tick.
        scanner.refreshFromCache()
    }

    fun trackAp(bssid: String?) {
        _trackedBssid.value = bssid
        _history.value = emptyList()
    }

    private fun recordHistory(state: WifiState) {
        val bssid = _trackedBssid.value ?: state.connectedBssid ?: return
        val rssi = if (bssid == state.connectedBssid && state.connectedRssi != null) {
            state.connectedRssi
        } else {
            state.accessPoints.firstOrNull { it.bssid == bssid }?.rssi
        } ?: return
        _history.value = (_history.value + rssi).takeLast(HISTORY_LENGTH)
    }

    // ------------------------------------------------------------------ survey

    fun setRoomSize(widthMeters: Float, heightMeters: Float) {
        val w = widthMeters.coerceIn(1f, 60f)
        val h = heightMeters.coerceIn(1f, 60f)
        walkTracker.boundsWidth = w
        walkTracker.boundsHeight = h
        updateSurvey { it.copy(widthMeters = w, heightMeters = h) }
    }

    fun setSurveyName(name: String) = updateSurvey { it.copy(name = name) }

    /** Record every AP currently visible at the given plan position. */
    fun addSampleAt(x: Float, y: Float) {
        val visible: List<AccessPoint> = wifiState.value.accessPoints
        if (visible.isEmpty()) {
            _ui.value = _ui.value.copy(message = "No scan results yet — wait for the first scan")
            return
        }
        val connected = wifiState.value
        val readings = visible.associate { ap ->
            // The connected AP reports live RSSI outside the scan cache, so
            // prefer that fresher number when it is available.
            val live = if (ap.bssid == connected.connectedBssid) connected.connectedRssi else null
            ap.bssid to (live ?: ap.rssi)
        }
        val names = visible.associate { it.bssid to it.displayName }
        val sample = SamplePoint(
            id = nextSampleId++,
            x = x.coerceIn(0f, _ui.value.survey.widthMeters),
            y = y.coerceIn(0f, _ui.value.survey.heightMeters),
            takenAtMillis = System.currentTimeMillis(),
            readings = readings,
            names = names
        )
        updateSurvey { it.copy(samples = it.samples + sample) }
    }

    fun removeSampleNear(x: Float, y: Float, radiusMeters: Float) {
        val survey = _ui.value.survey
        val victim = survey.samples.minByOrNull { s ->
            val dx = s.x - x
            val dy = s.y - y
            dx * dx + dy * dy
        } ?: return
        val dx = victim.x - x
        val dy = victim.y - y
        if (dx * dx + dy * dy <= radiusMeters * radiusMeters) {
            updateSurvey { it.copy(samples = it.samples - victim) }
        }
    }

    fun clearSamples() = updateSurvey { it.copy(samples = emptyList()) }

    fun clearWalls() = updateSurvey { it.copy(walls = emptyList()) }

    fun toggleWallDrawing() {
        _ui.value = _ui.value.copy(
            drawingWalls = !_ui.value.drawingWalls,
            pendingWallStart = null
        )
    }

    /** Two taps make a wall: the first anchors, the second closes the segment. */
    fun wallTap(x: Float, y: Float) {
        val start = _ui.value.pendingWallStart
        if (start == null) {
            _ui.value = _ui.value.copy(pendingWallStart = x to y)
        } else {
            val wall = Wall(start.first, start.second, x, y)
            _ui.value = _ui.value.copy(pendingWallStart = null)
            updateSurvey { it.copy(walls = it.walls + wall) }
        }
    }

    /** Draw the four walls of a plain rectangular room. */
    fun addPerimeterWalls() {
        val s = _ui.value.survey
        val w = s.widthMeters
        val h = s.heightMeters
        val perimeter = listOf(
            Wall(0f, 0f, w, 0f),
            Wall(w, 0f, w, h),
            Wall(w, h, 0f, h),
            Wall(0f, h, 0f, 0f)
        )
        updateSurvey { it.copy(walls = it.walls + perimeter) }
    }

    // ----------------------------------------------------------------- heatmap

    fun setTarget(target: HeatmapTarget) {
        _ui.value = _ui.value.copy(target = target)
        recompute()
    }

    fun setInfluenceRadius(meters: Float) {
        _ui.value = _ui.value.copy(influenceRadius = meters.coerceIn(1f, 12f))
        recompute()
    }

    fun toggleContours() {
        _ui.value = _ui.value.copy(showContours = !_ui.value.showContours)
    }

    fun toggleSampleMarkers() {
        _ui.value = _ui.value.copy(showSamples = !_ui.value.showSamples)
    }

    /** Samples flattened for the current target — the interpolator's input. */
    fun fieldSamples(state: SurveyUiState = _ui.value): List<FieldSample> =
        state.survey.samples.mapNotNull { sample ->
            sample.rssiForTarget(state.target)?.let { rssi ->
                FieldSample(sample.x, sample.y, rssi.toFloat())
            }
        }

    private fun recompute() {
        computeJob?.cancel()
        val snapshot = _ui.value
        val samples = fieldSamples(snapshot)
        if (samples.isEmpty()) {
            _ui.value = snapshot.copy(field = null, stats = null, computing = false)
            return
        }
        _ui.value = snapshot.copy(computing = true)
        computeJob = viewModelScope.launch {
            val field = withContext(Dispatchers.Default) {
                HeatmapEngine.interpolate(
                    samples = samples,
                    widthMeters = snapshot.survey.widthMeters,
                    heightMeters = snapshot.survey.heightMeters,
                    influenceRadiusMeters = snapshot.influenceRadius
                )
            }
            val stats = withContext(Dispatchers.Default) { HeatmapEngine.stats(field, samples) }
            _ui.value = _ui.value.copy(field = field, stats = stats, computing = false)
        }
    }

    fun renderBitmap(): Bitmap? = _ui.value.field?.let { HeatmapEngine.render(it) }

    /** Label for the current target, used in exports and headers. */
    fun targetLabel(): String = when (val t = _ui.value.target) {
        is HeatmapTarget.SingleAp -> _ui.value.survey.nameFor(t.bssid)
        is HeatmapTarget.Network -> t.ssid
        HeatmapTarget.BestAvailable -> "Best available signal"
    }

    // ------------------------------------------------------------- persistence

    fun saveSurvey() {
        val result = store.save(_ui.value.survey)
        _ui.value = _ui.value.copy(
            message = result.fold(
                onSuccess = { "Saved as $it" },
                onFailure = { "Save failed: ${it.message}" }
            )
        )
        refreshSavedList()
    }

    fun loadSurvey(fileName: String) {
        store.load(fileName).fold(
            onSuccess = { survey ->
                nextSampleId = (survey.samples.maxOfOrNull { it.id } ?: 0L) + 1
                walkTracker.boundsWidth = survey.widthMeters
                walkTracker.boundsHeight = survey.heightMeters
                _ui.value = _ui.value.copy(survey = survey, message = "Loaded ${survey.name}")
                recompute()
            },
            onFailure = { _ui.value = _ui.value.copy(message = "Could not open that survey") }
        )
    }

    fun deleteSurvey(fileName: String) {
        store.delete(fileName)
        refreshSavedList()
    }

    fun newSurvey() {
        nextSampleId = 1L
        _ui.value = _ui.value.copy(
            survey = Survey(),
            field = null,
            stats = null,
            target = HeatmapTarget.BestAvailable,
            message = "Started a new survey"
        )
    }

    private fun refreshSavedList() {
        _ui.value = _ui.value.copy(savedSurveys = store.list())
    }

    fun consumeMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    // -------------------------------------------------------------- walk mode

    fun setAutoSample(enabled: Boolean) {
        _autoSample.value = enabled
    }

    fun setStride(meters: Float) {
        walkTracker.strideMeters = meters.coerceIn(0.3f, 1.2f)
    }

    fun startWalk(x: Float, y: Float) {
        val survey = _ui.value.survey
        walkTracker.boundsWidth = survey.widthMeters
        walkTracker.boundsHeight = survey.heightMeters
        walkTracker.start(x, y)
        walkTracker.calibrateHeadingToPlanUp()
    }

    fun stopWalk() = walkTracker.stop()

    /** Drop the walk cursor onto a known spot to cancel accumulated drift. */
    fun moveWalkCursor(x: Float, y: Float) = walkTracker.moveTo(x, y)

    fun calibrateHeading() = walkTracker.calibrateHeadingToPlanUp()

    // ---------------------------------------------------------------- internal

    private fun updateSurvey(transform: (Survey) -> Survey) {
        _ui.value = _ui.value.copy(survey = transform(_ui.value.survey))
        recompute()
    }

    private companion object {
        const val HISTORY_LENGTH = 150
    }
}
