package com.wifisurvey.analyzer.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.wifisurvey.analyzer.survey.Survey
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes survey artefacts to cache and hands them to the system share sheet. */
object Exporter {

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** One row per sample per AP — ready for a spreadsheet or a plotting script. */
    fun writeCsv(context: Context, survey: Survey): File {
        val file = File(cacheDir(context), "wifi-survey-${stamp()}.csv")
        file.bufferedWriter().use { out ->
            out.appendLine("sample_id,x_m,y_m,taken_at_iso,ssid,bssid,rssi_dbm")
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            survey.samples.forEach { sample ->
                val takenAt = iso.format(Date(sample.takenAtMillis))
                sample.readings.forEach { (bssid, rssi) ->
                    val ssid = (sample.names[bssid] ?: "").replace(',', ' ').replace('"', '\'')
                    out.appendLine(
                        "${sample.id},${sample.x},${sample.y},$takenAt,\"$ssid\",$bssid,$rssi"
                    )
                }
            }
        }
        return file
    }

    /** The rendered heatmap as a shareable PNG. */
    fun writePng(context: Context, bitmap: Bitmap, label: String): File {
        val safe = label.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val file = File(cacheDir(context), "wifi-heatmap-$safe-${stamp()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    /** The full survey as JSON, so a run can be moved between devices. */
    fun writeJson(context: Context, survey: Survey): File {
        val file = File(cacheDir(context), "wifi-survey-${stamp()}.json")
        file.writeText(survey.toJson().toString(2))
        return file
    }

    fun share(context: Context, file: File, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
