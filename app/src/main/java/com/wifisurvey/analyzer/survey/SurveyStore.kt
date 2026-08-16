package com.wifisurvey.analyzer.survey

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Saved-survey metadata for the load dialog. */
data class SavedSurvey(val fileName: String, val name: String, val savedAtMillis: Long, val samples: Int)

/** Plain JSON files in the app's private storage — no database needed. */
class SurveyStore(context: Context) {

    private val dir = File(context.filesDir, "surveys").apply { mkdirs() }

    fun save(survey: Survey): Result<String> = runCatching {
        val stamped = survey.copy(savedAtMillis = System.currentTimeMillis())
        val fileName = sanitize(survey.name) + ".json"
        File(dir, fileName).writeText(stamped.toJson().toString())
        fileName
    }

    fun load(fileName: String): Result<Survey> = runCatching {
        Survey.fromJson(JSONObject(File(dir, fileName).readText()))
    }

    fun delete(fileName: String): Boolean = File(dir, fileName).delete()

    fun list(): List<SavedSurvey> =
        dir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    SavedSurvey(
                        fileName = file.name,
                        name = json.optString("name", file.nameWithoutExtension),
                        savedAtMillis = json.optLong("saved"),
                        samples = json.optJSONArray("samples")?.length() ?: 0
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.savedAtMillis }
            ?: emptyList()

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '_')
        return cleaned.ifBlank { "survey_" + System.currentTimeMillis() }
    }
}
