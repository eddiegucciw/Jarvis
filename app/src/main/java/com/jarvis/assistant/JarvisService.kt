package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Hands-free Jarvis: runs in the background, listens for "Jarvis",
 * then takes a command and keeps the conversation going until you go quiet.
 */
class JarvisService : Service() {

    companion object {
        const val ACTION_START = "com.jarvis.assistant.START"
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        const val ACTION_TALK = "com.jarvis.assistant.TALK"
        const val ACTION_LOG = "com.jarvis.assistant.LOG"
        const val ACTION_STATE = "com.jarvis.assistant.STATE"
        private const val CHANNEL = "jarvis_handsfree"
        private const val NOTIF_ID = 7

        @Volatile
        var running = false
            private set
    }

    private enum class Mode { WAKE, COMMAND, BUSY }

    private var mode = Mode.WAKE
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var actions: Actions
    private var brain: Brain? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var muted = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TALK -> {
                if (running) {
                    recognizer?.cancel()
                    say("Yes?", Mode.COMMAND)
                }
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY

        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val key = prefs.getString("api_key", "") ?: ""
        if (key.isBlank() || !SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startInForeground()
        } catch (e: Exception) {
            // Android refused to start (e.g. microphone permission missing).
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        stopping = false
        prefs.edit().putBoolean("hands_free", true).apply()
        sendState()

        actions = Actions(this)
        brain = Brain(key, Jarvis.model(prefs))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:handsfree").apply {
            setReferenceCounted(false)
            acquire()
        }
        setupVoice()
        recognizer = createRecognizer()
        handler.postDelayed({ listen(Mode.WAKE) }, 800)
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis hands-free", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification("Say \"Jarvis\" to wake me")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val talk = PendingIntent.getService(
            this, 2, Intent(this, JarvisService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis is listening")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // ------------------------------------------------------------------ voice

    private fun setupVoice() {
        tts = TextToSpeech(this) { result ->
            val engine = tts
            if (result == TextToSpeech.SUCCESS && engine != null) {
                val r = engine.setLanguage(Locale.UK)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.getDefault())
                }
                engine.setSpeechRate(1.05f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post { afterSpeaking(utteranceId) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { afterSpeaking(utteranceId) }
                    }
                })
                ttsReady = true
            }
        }
    }

    private fun say(text: String, next: Mode) {
        if (!running) return
        mode = Mode.BUSY
        log("Jarvis", text)
        updateNotification(text)
        mute(false)
        val id = if (next == Mode.COMMAND) "command" else "wake"
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } else {
            handler.postDelayed({ afterSpeaking(id) }, 300)
        }
    }

    private fun afterSpeaking(utteranceId: String?) {
        val next = if (utteranceId == "command") Mode.COMMAND else Mode.WAKE
        handler.postDelayed({ listen(next) }, 250)
    }

    /** Silences the recogniser's beep while waiting for the wake word. */
    private fun mute(on: Boolean) {
        if (muted == on) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
            muted = on
        } catch (e: Exception) {
            // Some phones don't allow this; the beep will just be audible.
        }
    }

    // ------------------------------------------------------------------ ears

    private fun createRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                    onHeard(heard)
                }

                override fun onError(error: Int) {
                    if (!running) return
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            if (mode == Mode.COMMAND) updateNotification("Say \"Jarvis\" to wake me")
                            handler.postDelayed({ listen(Mode.WAKE) }, 250)
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_CLIENT -> resetRecognizer()
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> shutdown()
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> {
                            updateNotification("No internet - retrying…")
                            handler.postDelayed({ listen(Mode.WAKE) }, 4000)
                        }
                        else -> handler.postDelayed({ listen(Mode.WAKE) }, 1000)
                    }
                }
            })
        }

    private fun resetRecognizer() {
        recognizer?.destroy()
        recognizer = null
        handler.postDelayed({
            if (running) {
                recognizer = createRecognizer()
                listen(Mode.WAKE)
            }
        }, 1000)
    }

    private fun listen(m: Mode) {
        if (!running || stopping) return
        val rec = recognizer ?: return
        mode = m
        mute(m == Mode.WAKE)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        if (m == Mode.COMMAND) updateNotification("Listening for your command…")
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            resetRecognizer()
        }
    }

    private fun onHeard(heard: List<String>) {
        when (mode) {
            Mode.WAKE -> {
                val command = heard.firstNotNullOfOrNull { Jarvis.afterWakeWord(it) }
                when {
                    command == null -> handler.postDelayed({ listen(Mode.WAKE) }, 150)
                    command.isBlank() -> say("Yes?", Mode.COMMAND)
                    else -> handleCommand(command)
                }
            }
            Mode.COMMAND -> {
                val text = heard.firstOrNull()?.trim()
                if (text.isNullOrBlank()) listen(Mode.WAKE) else handleCommand(text)
            }
            Mode.BUSY -> {}
        }
    }

    // ------------------------------------------------------------------ brain + hands

    private fun handleCommand(text: String) {
        mode = Mode.BUSY
        log("You", text)
        if (Jarvis.isGoodbye(text)) {
            say("Very well. Just say Jarvis when you need me.", Mode.WAKE)
            return
        }
        val b = brain ?: return
        updateNotification("Thinking…")
        val prompt = Jarvis.systemPrompt()
        Thread {
            val reply = b.ask(text, prompt)
            handler.post {
                if (!running) return@post
                val action = Jarvis.parseAction(reply)
                val out = if (action != null) actions.run(action) else reply
                // Keep listening for a follow-up; silence sends Jarvis back to sleep.
                say(out, Mode.COMMAND)
            }
        }.start()
    }

    private fun log(who: String, text: String) {
        sendBroadcast(
            Intent(ACTION_LOG).setPackage(packageName)
                .putExtra("who", who)
                .putExtra("text", text)
        )
    }

    private fun sendState() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    // ------------------------------------------------------------------ shutdown

    private fun shutdown() {
        if (stopping) return
        stopping = true
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        mute(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("hands_free", false).apply()
        sendState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
