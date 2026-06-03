package com.notifclassifier.network

import android.util.Log
import com.notifclassifier.model.ClassifyRequest
import com.notifclassifier.model.ClassifyResponse
import com.notifclassifier.model.FeedbackRequest
import com.notifclassifier.model.FeedbackResponse
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight HTTP client using only java.net.HttpURLConnection.
 * Zero extra Gradle dependencies required.
 *
 * ╔══════════════════════════════════════════════════════╗
 * ║  CHANGE THIS URL TO YOUR OWN BACKEND IF NEEDED       ║
 * ╚══════════════════════════════════════════════════════╝
 */
object ApiClient {

    // ▼▼▼  BACKEND URL — change here if you redeploy  ▼▼▼
    private const val BASE_URL = "https://faiyaz029-notif-classifier.hf.space"
    // ▲▲▲  BACKEND URL ▲▲▲

    private const val TIMEOUT_MS = 15_000   // 15 second timeout

    private val TAG = "ApiClient"

    // ─── Health check ──────────────────────────────────────────────────────────

    fun healthCheck(): Boolean {
        return try {
            val conn = openConnection("$BASE_URL/", "GET")
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed: ${e.message}")
            false
        }
    }

    // ─── POST /classify ────────────────────────────────────────────────────────

    /**
     * Returns ClassifyResponse on success, throws ApiException on failure.
     */
    fun classify(request: ClassifyRequest): ClassifyResponse {
        val body = JSONObject().apply {
            put("app_name", request.app_name)
            put("user_name", request.user_name)
            put("content", request.content)
        }.toString()

        val (responseCode, responseBody) = post("$BASE_URL/classify", body)

        if (responseCode != 200) {
            throw ApiException("Server returned HTTP $responseCode", responseCode)
        }

        return try {
            val json = JSONObject(responseBody)
            ClassifyResponse(
                decision_code = json.getInt("decision_code"),
                decision_label = json.getString("decision_label"),
                confidence = json.getDouble("confidence").toFloat()
            )
        } catch (e: Exception) {
            throw ApiException("Failed to parse classify response: ${e.message}", responseCode)
        }
    }

    // ─── POST /feedback ────────────────────────────────────────────────────────

    /**
     * Returns FeedbackResponse on success, throws ApiException on failure.
     */
    fun feedback(request: FeedbackRequest): FeedbackResponse {
        val body = JSONObject().apply {
            put("app_name", request.app_name)
            put("user_name", request.user_name)
            put("content", request.content)
            put("decision_code", request.decision_code)
            put("user_rating", request.user_rating)
        }.toString()

        val (responseCode, responseBody) = post("$BASE_URL/feedback", body)

        if (responseCode != 200) {
            throw ApiException("Server returned HTTP $responseCode", responseCode)
        }

        return try {
            val json = JSONObject(responseBody)
            FeedbackResponse(
                success = json.getBoolean("success"),
                message = json.getString("message"),
                log_id = json.optInt("log_id", -1)
            )
        } catch (e: Exception) {
            throw ApiException("Failed to parse feedback response: ${e.message}", responseCode)
        }
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private fun post(urlString: String, jsonBody: String): Pair<Int, String> {
        val conn = openConnection(urlString, "POST")
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(jsonBody)
            writer.flush()
        }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val responseBody = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            .use { it.readText() }

        conn.disconnect()
        return Pair(responseCode, responseBody)
    }

    private fun openConnection(urlString: String, method: String): HttpURLConnection {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }
}

class ApiException(message: String, val httpCode: Int = -1) : Exception(message)
