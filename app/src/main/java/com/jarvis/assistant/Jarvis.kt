package com.jarvis.assistant

import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared Jarvis personality and helpers, used by the screen and the hands-free service. */
object Jarvis {

    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    private val RETIRED_MODELS = setOf("", "gemini-2.5-flash")

    private val GOODBYES = setOf(
        "goodbye", "bye", "goodbye jarvis", "bye jarvis", "stop",
        "that's all", "thats all", "thank you that's all", "never mind", "nevermind"
    )

    /** "Jarvis", "Hey Jarvis", "OK Jarvis", plus common mishearings. */
    private val WAKE = Regex(
        """\b(?:hey |hi |ok |okay )?(?:jarvis|jervis|javis|jarvi|jarvas|jarves)\b[\s,.!?]*""",
        RegexOption.IGNORE_CASE
    )

    fun model(prefs: SharedPreferences): String {
        val m = prefs.getString("model", DEFAULT_MODEL)?.trim() ?: DEFAULT_MODEL
        return if (m in RETIRED_MODELS) DEFAULT_MODEL else m
    }

    fun isGoodbye(text: String): Boolean =
        text.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?') in GOODBYES

    /**
     * If the text contains the wake word, returns whatever was said after it
     * ("" if only the wake word). Returns null if there is no wake word.
     */
    fun afterWakeWord(text: String): String? {
        val m = WAKE.find(text) ?: return null
        return text.substring(m.range.last + 1).trim()
    }

    fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        return """
            You are Jarvis, a witty, loyal, slightly formal personal assistant living on the user's Android phone.
            The current date and time is $now.
            Your replies are spoken aloud, so keep them short (1-3 sentences) and natural.
            Never use markdown, bullet points, asterisks or emoji.

            You can control the phone. When the user asks for one of these actions, reply with ONLY
            a single-line JSON object and nothing else:
              {"action":"torch_on"}
              {"action":"torch_off"}
              {"action":"battery"}
              {"action":"clipboard"}
              {"action":"vibrate"}
              {"action":"open_app","name":"WhatsApp"}
              {"action":"set_alarm","hour":6,"minute":30,"label":"Wake up"}      (24-hour clock)
              {"action":"set_timer","seconds":300,"label":"Tea"}
              {"action":"web_search","query":"..."}
              {"action":"dial","number":"0712345678"}
              {"action":"sms","number":"0712345678","message":"..."}
            For everything else, just answer normally.
        """.trimIndent()
    }

    /** Returns the action JSON if the brain replied with one, otherwise null. */
    fun parseAction(reply: String): JSONObject? {
        val t = reply.trim()
        if (!t.startsWith("{") && !t.startsWith("```")) return null
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(t.substring(start, end + 1)).takeIf { it.has("action") }
        } catch (e: Exception) {
            null
        }
    }
}
