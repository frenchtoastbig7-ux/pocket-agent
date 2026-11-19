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

    /** Bumped whenever recognition is started or cancelled; callbacks from an older session are dropped. */
    private var listenSession = 0

    val onDeviceRecognition: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val anyRecognition: Boolean get() = onDeviceRecognition || SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        cancelListening()
        val session = listenSession
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
                if (session != listenSession) return
                onFinal(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }

            override fun onPartialResults(partialResults: Bundle) {
                if (session != listenSession) return
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }

            override fun onError(error: Int) {
                if (session != listenSession) return
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

    /** Drops the current recognition without a result. stopListening() would still deliver one. */
    fun cancelListening() {
        listenSession++
        recognizer?.let {
            runCatching { it.cancel() }
            it.destroy()
        }
        recognizer = null
    }

    fun speak(text: String) {
        val engine = tts
        if (engine == null) {
            queued = text
            // The engine may report its result before the constructor returns, so the result is handled only once
            // the new engine has been stored.
            var created: TextToSpeech? = null
            var early: Int? = null
            fun finish(made: TextToSpeech, status: Int) {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    queued?.let(::say)
                } else {
                    // Let go of the failed engine so the next reply tries again (one may have been installed since).
                    made.shutdown()
                    if (tts === made) tts = null
                }
                queued = null
            }
            val made = TextToSpeech(context) { status -> created?.let { finish(it, status) } ?: run { early = status } }
            created = made
            tts = made
            early?.let { finish(made, it) }
        } else if (ttsReady) {
            say(text)
        }
    }

    /** Engines refuse input over their length limit, so long replies go in as a queue of pieces. */
    private fun say(text: String) {
        val engine = tts ?: return
        val limit = (TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtLeast(200)
        var rest = text.trim()
        var mode = TextToSpeech.QUEUE_FLUSH
        var part = 0
        while (rest.isNotEmpty()) {
            var cut = rest.length
            if (cut > limit) {
                // Prefer the end of a sentence, then any space, in the last part of the window.
                val window = rest.substring(0, limit)
                cut = sequenceOf(". ", "\n", " ").map { window.lastIndexOf(it) }.firstOrNull { it > limit / 2 }?.plus(1) ?: limit
            }
            if (engine.speak(rest.substring(0, cut), mode, null, "reply-${part++}") != TextToSpeech.SUCCESS) return
            rest = rest.substring(cut).trimStart()
            mode = TextToSpeech.QUEUE_ADD
        }
    }

    fun shutdown() {
        cancelListening()
        tts?.shutdown()
        tts = null
    }
}
