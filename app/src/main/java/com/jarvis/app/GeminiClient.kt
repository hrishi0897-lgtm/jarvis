package com.jarvis.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiClient(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String
) {

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateContent(
        contents: JSONArray,
        tools: JSONArray?,
        systemInstruction: String?
    ): JSONObject = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            throw IllegalStateException("Gemini API key is not configured. Add it in the Settings tab.")
        }

        val model = modelProvider().ifBlank { PreferencesManager.DEFAULT_MODEL }
        val url = "$BASE_URL$model:generateContent"

        val payload = JSONObject().apply {
            put("contents", contents)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
            }
            if (!systemInstruction.isNullOrBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
            }
        }

        val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: responseBody
                } catch (_: Exception) {
                    responseBody
                }
                if (response.code == 429) {
                    throw IOException("Rate limit hit (429). The model is temporarily throttled or quota was reached. Switch models in Settings or wait a few seconds.")
                }
                throw IOException("Gemini API error (${response.code}): $errorMsg")
            }
            JSONObject(responseBody)
        }
    }
}
