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
        if (tts == null) tts = TextToSpeech(context, this)
        else tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "truthcore-response")
    }

    fun stop() {
        recognizer?.stopListening()
        onState("Idle")
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isNotBlank()) onTranscript(text)
        onState("Idle")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onTranscript)
    }

    override fun onError(error: Int) = onState("Voice error $error")
    override fun onReadyForSpeech(params: Bundle?) = onState("Listening")
    override fun onBeginningOfSpeech() = onState("Hearing speech")
    override fun onEndOfSpeech() = onState("Processing")
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
