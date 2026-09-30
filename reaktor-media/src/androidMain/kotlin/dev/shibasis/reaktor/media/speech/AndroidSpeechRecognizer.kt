package dev.shibasis.reaktor.media.speech

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidRecognizer
import androidx.activity.ComponentActivity

/**
 * Android [SpeechRecognizer] over the platform `android.speech.SpeechRecognizer`. Needs RECORD_AUDIO
 * (declared in this module's manifest, requested at runtime by the app). The platform recognizer **must
 * be created and driven on the main thread**, so build this on the main thread and call [start] / [stop]
 * / [cancel] from it. Register with `Feature.SpeechRecognizer = AndroidSpeechRecognizer(activity)` and
 * release with [shutdown].
 */
class AndroidSpeechRecognizer(
    activity: ComponentActivity,
) : SpeechRecognizer<ComponentActivity>(activity) {

    private val appContext = activity.applicationContext
    private val recognizer = AndroidRecognizer.createSpeechRecognizer(appContext)
    private var listening = false

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                listening = false
                val text = results.getStringArrayList(AndroidRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) emit(RecognitionEvent.Error(RecognitionError.NoMatch))
                else emit(RecognitionEvent.Result(text))
            }

            override fun onPartialResults(partialResults: Bundle) {
                val text = partialResults.getStringArrayList(AndroidRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) emit(RecognitionEvent.Partial(text))
            }

            override fun onError(error: Int) {
                listening = false
                emit(RecognitionEvent.Error(mapError(error)))
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    override fun start(request: RecognitionRequest) {
        if (listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, request.partialResults)
            request.language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            if (request.preferOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        listening = true
        recognizer.startListening(intent)
    }

    override fun stop() {
        listening = false
        recognizer.stopListening()
    }

    override fun cancel() {
        listening = false
        recognizer.cancel()
    }

    override fun isListening(): Boolean = listening

    override fun isAvailable(): Boolean = AndroidRecognizer.isRecognitionAvailable(appContext)

    override fun shutdown() {
        listening = false
        recognizer.destroy()
    }

    /** Map the platform's integer error codes to the typed [RecognitionError] set. */
    private fun mapError(code: Int): RecognitionError = when (code) {
        AndroidRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError.PermissionDenied
        AndroidRecognizer.ERROR_NETWORK, AndroidRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionError.Network
        AndroidRecognizer.ERROR_NO_MATCH -> RecognitionError.NoMatch
        AndroidRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.Timeout
        AndroidRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionError.Busy
        else -> RecognitionError.Unknown("Android SpeechRecognizer error code=$code")
    }
}
