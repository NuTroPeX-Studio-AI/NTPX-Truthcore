package com.ntpx.truthcore.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ntpx.truthcore.MainActivity
import com.ntpx.truthcore.core.TruthCoreRuntime
import com.ntpx.truthcore.core.model.ModelSession
import java.util.Locale
import java.util.concurrent.Executors

class VoiceForegroundService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var stopped = false
    private lateinit var runtime: TruthCoreRuntime

    override fun onCreate() {
        super.onCreate()
        runtime = TruthCoreRuntime(applicationContext)
        createChannel()
        tts = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopped = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        stopped = false
        promoteToForeground()
        beginListening()
        return START_STICKY
    }

    private fun promoteToForeground() {
        val openPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NTPX TruthCore hands-free")
            .setContentText("Listening for ‘Hey TruthCore’. Tap to open; Stop ends the session.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPending).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginListening() {
        if (stopped || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val speech = recognizer ?: return
        runCatching { speech.cancel() }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { speech.startListening(intent) }
            .onFailure { scheduleRestart() }
    }

    override fun onResults(results: Bundle?) {
        val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
        val command = extractWakeCommand(transcript)
        if (command == null) {
            scheduleRestart(250)
            return
        }
        if (command.isBlank()) {
            speak("Yes?")
            return
        }

        runCatching { recognizer?.cancel() }
        executor.execute {
            val reply = runtime.respond(command, ModelSession.provider)
            main.post { speak(reply.text) }
        }
    }

    private fun extractWakeCommand(transcript: String): String? {
        val lower = transcript.lowercase(Locale.ROOT)
        val phrases = listOf("hey truthcore", "hey truth core", "truthcore", "truth core")
        val match = phrases.mapNotNull { phrase -> lower.indexOf(phrase).takeIf { it >= 0 }?.let { it to phrase } }
            .minByOrNull { it.first } ?: return null
        return transcript.substring(match.first + match.second.length).trim().trimStart(',', ':', '-', ' ')
    }

    private fun speak(text: String) {
        val clean = text.trim().take(6000)
        if (clean.isBlank()) {
            scheduleRestart()
            return
        }
        runCatching { recognizer?.cancel() }
        if (!ttsReady) {
            pendingSpeech = clean
            return
        }
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            pendingSpeech = null
            scheduleRestart()
            return
        }
        val language = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsReady = false
            pendingSpeech = null
            scheduleRestart()
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post { scheduleRestart(250) } }
            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) { main.post { scheduleRestart() } }
        })
        ttsReady = true
        pendingSpeech?.let {
            pendingSpeech = null
            speak(it)
        } ?: scheduleRestart(250)
    }

    override fun onError(error: Int) {
        if (stopped) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_CLIENT -> Unit
            else -> scheduleRestart()
        }
    }

    private fun scheduleRestart(delayMs: Long = 900) {
        if (stopped) return
        main.removeCallbacksAndMessages(RESTART_TOKEN)
        main.postAtTime({ beginListening() }, RESTART_TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        runtime.close()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "TruthCore voice", NotificationManager.IMPORTANCE_LOW).apply {
                description = "User-started TruthCore hands-free voice session"
            }
        )
    }

    companion object {
        const val ACTION_STOP = "com.ntpx.truthcore.voice.STOP"
        private const val CHANNEL_ID = "truthcore_voice"
        private const val NOTIFICATION_ID = 1001
        private const val UTTERANCE_ID = "truthcore-hands-free"
        private val RESTART_TOKEN = Any()
    }
}
