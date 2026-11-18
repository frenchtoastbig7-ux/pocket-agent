package dev.pocketagent.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech

/**
 * Push-to-talk and spoken replies using whatever engines are installed. GrapheneOS ships neither by default:
 * on-device recognition is preferred when a recognizer offers it, and TTS needs an engine such as RHVoice or
 * SherpaTTS from F-Droid / Accrescent.
 */
class Voice(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queued: String? = null

    val onDeviceRecognition: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val anyRecognition: Boolean get() = onDeviceRecognition || SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        recognizer?.destroy()
        val r = when {
            onDeviceRecognition -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> {
                onError("No speech recognizer is installed. Install one that provides a recognition service, then try again.")
                return
            }
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                onFinal(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }

            override fun onPartialResults(partialResults: Bundle) {
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }

            override fun onError(error: Int) {
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The recognizer needs a network connection."
                        else -> "Speech recognition failed (code $error)."
                    }
                )
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        )
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun speak(text: String) {
        val engine = tts
        if (engine == null) {
            queued = text
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) queued?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "reply") }
                queued = null
            }
        } else if (ttsReady) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        }
    }

    fun shutdown() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }
}
