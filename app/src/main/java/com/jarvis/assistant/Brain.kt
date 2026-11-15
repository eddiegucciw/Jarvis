package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jarvis's brain: Google Gemini (free tier).
 * To swap brains later (Claude API, a local model on your PC...), rewrite only ask().
 */
class Brain(private val apiKey: String, private val model: String) {

    private val history = mutableListOf<JSONObject>()

    private fun message(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    /** Blocking network call: always run this off the main thread. */
    fun ask(userText: String, systemPrompt: String): String {
        history.add(message("user", userText))

        val contents = JSONArray()
        history.takeLast(20).forEach { contents.put(it) }

        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put("contents", contents)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)

        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                history.removeAt(history.lastIndex)
                when (code) {
                    429 -> "I've hit my free usage limit for now. Give me a minute."
                    400, 401, 403 -> "My API key doesn't seem to work. Please check it in settings."
                    404 -> "I can't find the model $model. Please check the model name in settings."
                    else -> "My brain returned error $code. Try again in a moment."
                }
            } else {
                val text = conn.inputStream.bufferedReader().readText()
                val reply = JSONObject(text)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0)
                    .getString("text").trim()
                history.add(message("model", reply))
                reply
            }
        } catch (e: Exception) {
            if (history.isNotEmpty()) history.removeAt(history.lastIndex)
            "Sorry, I can't reach my brain. Check your internet connection."
        } finally {
            conn.disconnect()
        }
    }

    fun forget() = history.clear()
}
