package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val CYAN = "#22D3EE"
        private const val BG = "#0A0F1A"
        private const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val REQ_MIC = 1
    }

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var micButton: Button

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var actions: Actions
    private var brain: Brain? = null

    private var listening = false
    private var thinking = false
    private var conversationOn = false   // keep listening after each reply

    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor(BG)
        window.navigationBarColor = Color.parseColor(BG)
        setContentView(buildUi())

        actions = Actions(this)
        loadBrain()
        setupVoice()
        setupEars()

        if (prefs.getString("api_key", "").isNullOrBlank()) {
            log("Jarvis", "Welcome. First, paste your free Gemini API key in Settings.")
            showSettings()
        } else {
            log("Jarvis", "Online. Tap the ring and talk to me.")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG))
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            setTextColor(Color.parseColor(CYAN))
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.1f
        }
        val settings = TextView(this).apply {
            text = "SETTINGS"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { showSettings() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(settings)
        root.addView(header)

        status = TextView(this).apply {
            text = "Idle"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(status)

        transcript = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 16f
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        scroll = ScrollView(this).apply { addView(transcript) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        micButton = Button(this).apply {
            text = "TAP TO TALK"
            setTextColor(Color.parseColor(CYAN))
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            stateListAnimator = null
            setOnClickListener { onMicTapped() }
        }
        styleMic(active = false)
        val micParams = LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16)
        }
        root.addView(micButton, micParams)

        val hint = TextView(this).apply {
            text = "Say \"goodbye\" to stop the conversation"
            setTextColor(Color.parseColor("#475569"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun styleMic(active: Boolean) {
        micButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (active) "#0E3A47" else "#0F172A"))
            setStroke(dp(if (active) 6 else 3), Color.parseColor(CYAN))
        }
    }

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun log(who: String, text: String) {
        val line = "$who: $text\n\n"
        transcript.append(line)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun loadBrain() {
        val key = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        brain = if (key.isBlank()) null else Brain(key, model)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("api_key", ""))
        }
        val modelInput = EditText(this).apply {
            hint = "Model (default $DEFAULT_MODEL)"
            setText(prefs.getString("model", DEFAULT_MODEL))
        }
        box.addView(keyInput)
        box.addView(modelInput)

        AlertDialog.Builder(this)
            .setTitle("Jarvis settings")
            .setMessage("Get a free key at aistudio.google.com → Get API key.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("api_key", keyInput.text.toString().trim())
                    .putString("model", modelInput.text.toString().trim())
                    .apply()
                loadBrain()
                log("Jarvis", if (brain != null) "Settings saved. I'm ready." else "I still need an API key.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ voice (TTS)

    private fun setupVoice() {
        tts = TextToSpeech(this) { result ->
            val engine = tts
            if (result == TextToSpeech.SUCCESS && engine != null) {
                // A British voice for the proper Jarvis feel, if the phone has one.
                val r = engine.setLanguage(Locale.UK)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.getDefault())
                }
                engine.setSpeechRate(1.05f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { afterSpeaking(utteranceId) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { afterSpeaking(utteranceId) }
                    }
                })
                ttsReady = true
            }
        }
    }

    /** utteranceId "listen" means: start listening again after speaking. */
    private fun say(text: String, keepListening: Boolean) {
        log("Jarvis", text)
        val id = if (keepListening) "listen" else "stop"
        if (ttsReady) {
            setStatus("Speaking…")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } else {
            afterSpeaking(id)
        }
    }

    private fun afterSpeaking(utteranceId: String?) {
        if (utteranceId == "listen" && conversationOn && !isFinishing) {
            startListening()
        } else {
            conversationOn = false
            setStatus("Idle")
            styleMic(active = false)
        }
    }

    // ------------------------------------------------------------------ ears (speech recognition)

    private fun setupEars() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("Jarvis", "This phone has no speech recognition service. Install or enable the Google app.")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { setStatus("Listening…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { setStatus("Processing…") }
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) setStatus("“$partial”")
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (heard.isNullOrBlank()) stopConversation("I didn't catch that.") else handle(heard)
                }

                override fun onError(error: Int) {
                    listening = false
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> stopConversation(null)
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            stopConversation("I need microphone permission.")
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            stopConversation("Speech recognition needs internet.")
                        else -> stopConversation(null)
                    }
                }
            })
        }
    }

    private fun onMicTapped() {
        when {
            brain == null -> showSettings()
            listening -> {
                recognizer?.stopListening()
                stopConversation(null)
            }
            thinking -> {} // wait for the answer
            else -> {
                tts?.stop()
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                } else {
                    conversationOn = true
                    startListening()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                conversationOn = true
                startListening()
            } else {
                log("Jarvis", "I can't hear you without microphone permission.")
            }
        }
    }

    private fun startListening() {
        val rec = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        styleMic(active = true)
        setStatus("Listening…")
        rec.startListening(intent)
    }

    private fun stopConversation(message: String?) {
        conversationOn = false
        listening = false
        styleMic(active = false)
        setStatus("Idle")
        if (message != null) log("Jarvis", message)
    }

    // ------------------------------------------------------------------ brain + hands

    private fun systemPrompt(): String {
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

    private fun handle(heard: String) {
        log("You", heard)
        val lower = heard.lowercase(Locale.ROOT).trim().trimEnd('.', '!')
        if (lower in setOf("goodbye", "bye", "goodbye jarvis", "bye jarvis", "stop", "that's all")) {
            conversationOn = false
            say("Goodbye. I'll be here when you need me.", keepListening = false)
            return
        }
        val b = brain ?: return showSettings()
        thinking = true
        setStatus("Thinking…")
        val prompt = systemPrompt()
        Thread {
            val reply = b.ask(heard, prompt)
            runOnUiThread {
                thinking = false
                val action = parseAction(reply)
                if (action != null) {
                    val result = actions.run(action)
                    val stay = action.optString("action") !in actions.leavesApp
                    if (!stay) conversationOn = false
                    say(result, keepListening = stay)
                } else {
                    say(reply, keepListening = true)
                }
            }
        }.start()
    }

    /** Returns the action JSON if the brain replied with one, otherwise null. */
    private fun parseAction(reply: String): JSONObject? {
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

    // ------------------------------------------------------------------ lifecycle

    override fun onPause() {
        super.onPause()
        if (listening) recognizer?.cancel()
        stopConversation(null)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
