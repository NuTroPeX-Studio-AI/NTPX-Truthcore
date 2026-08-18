package com.ntpx.truthcore.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceController(
    private val context: Context,
    private val onTranscript: (String) -> Unit,
    private val onState: (String) -> Unit,
) : RecognitionListener, TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onState("Speech recognition unavailable")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(this) }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        onState("Listening")
        recognizer?.startListening(intent)
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return

        if (tts == null) {
            pendingSpeech = clean
            tts = TextToSpeech(context, this)
            onState("Preparing voice")
            return
        }

        if (!ttsReady) {
            pendingSpeech = clean
            return
        }

        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "truthcore-response")
    }

    fun stop() {
        recognizer?.stopListening()
        onState("Idle")
    }

    fun cancel() {
        recognizer?.cancel()
        onState("Idle")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        pendingSpeech = null
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            pendingSpeech = null
            onState("Text to speech unavailable")
            return
        }

        val languageResult = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsReady = false
            pendingSpeech = null
            onState("Voice language unavailable")
            return
        }

        ttsReady = true
        val queued = pendingSpeech
        pendingSpeech = null
        if (!queued.isNullOrBlank()) {
            tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "truthcore-response")
        }
        onState("Idle")
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isNotBlank()) onTranscript(text)
        onState(if (text.isBlank()) "No speech detected" else "Ready to send")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onTranscript)
    }

    override fun onError(error: Int) = onState(errorLabel(error))
    override fun onReadyForSpeech(params: Bundle?) = onState("Listening")
    override fun onBeginningOfSpeech() = onState("Hearing speech")
    override fun onEndOfSpeech() = onState("Processing speech")
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun errorLabel(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Voice session cancelled"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
        SpeechRecognizer.ERROR_NETWORK -> "Speech network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech service error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Voice error"
    }
}
