package com.notifclassifier.ui

import android.content.Context
import android.os.Environment
import com.notifclassifier.model.NotificationItem
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Thread-safe in-memory store for intercepted notifications.
 * Also handles CSV export.
 */
object NotificationRepository {

    // Newest-first; capacity capped to avoid unbounded memory growth
    private const val MAX_ITEMS = 500
    private val _items: MutableList<NotificationItem> =
        Collections.synchronizedList(mutableListOf())

    val items: List<NotificationItem> get() = _items.toList()

    fun add(item: NotificationItem) {
        _items.add(0, item)        // prepend — newest first
        if (_items.size > MAX_ITEMS) _items.removeAt(_items.size - 1)
    }

    fun findById(id: String): NotificationItem? = _items.find { it.id == id }

    fun clear() = _items.clear()

    // ─── CSV Export ────────────────────────────────────────────────────────────

    /**
     * Writes all classified notifications to a CSV file in Downloads.
     * Returns the absolute path of the written file, or null on error.
     */
    fun exportCsv(context: Context): String? {
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val timestamp = sdf.format(Date())
            val fileName = "notifications_$timestamp.csv"

            // Save to app-private files dir (no WRITE_EXTERNAL_STORAGE needed on API 29+)
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, fileName)

            FileWriter(file).use { writer ->
                // Header
                writer.append("id,app_label,package_name,title,text,post_time,decision_code,decision_label,confidence,user_rating\n")
                val sdfRow = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                synchronized(_items) {
                    for (item in _items) {
                        writer.append(csvEscape(item.id)).append(",")
                        writer.append(csvEscape(item.appLabel)).append(",")
                        writer.append(csvEscape(item.packageName)).append(",")
                        writer.append(csvEscape(item.title)).append(",")
                        writer.append(csvEscape(item.text)).append(",")
                        writer.append(sdfRow.format(Date(item.postTime))).append(",")
                        writer.append((item.decisionCode ?: "").toString()).append(",")
                        writer.append(csvEscape(item.decisionLabel ?: "")).append(",")
                        writer.append((item.confidence?.let { "%.4f".format(it) } ?: "")).append(",")
                        writer.append(if (item.selectedRating > 0) item.selectedRating.toString() else "")
                        writer.append("\n")
                    }
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun csvEscape(value: String): String {
        // RFC 4180: wrap in quotes and double any internal quotes
        val escaped = value.replace("\"", "\"\"").replace("\n", " ")
        return "\"$escaped\""
    }
}
