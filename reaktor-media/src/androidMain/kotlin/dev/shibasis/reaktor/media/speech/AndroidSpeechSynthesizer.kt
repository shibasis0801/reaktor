package dev.shibasis.reaktor.media.speech

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import java.util.Locale

/**
 * Android [SpeechSynthesizer] over the platform `TextToSpeech` engine.
 *
 * The word-boundary highlight relies on [UtteranceProgressListener.onRangeStart], which is API 26+;
 * on older devices speech still works but ranges won't stream, so reading-follow degrades to plain
 * playback. Register with `Feature.SpeechSynthesizer = AndroidSpeechSynthesizer(activity)` and call
 * [shutdown] from the owning lifecycle.
 */
class AndroidSpeechSynthesizer(
    activity: ComponentActivity,
) : SpeechSynthesizer<ComponentActivity>(activity) {

    @Volatile private var ready = false
    private var rate = 1.0f
    private var pending: Utterance? = null

    private data class Utterance(val text: String, val id: String)

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            emit(SpeechEvent.Started(utteranceId.orEmpty()))
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val id = utteranceId.orEmpty()
            emit(SpeechEvent.Range(id, SpokenRange(id, start, end)))
        }

        override fun onDone(utteranceId: String?) {
            emit(SpeechEvent.Done(utteranceId.orEmpty()))
        }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            emit(SpeechEvent.Error(utteranceId.orEmpty(), "TTS error"))
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            emit(SpeechEvent.Error(utteranceId.orEmpty(), "TTS error code=$errorCode"))
        }
    }

    // Init is async; the callback fires after construction, so referencing `tts` inside it is safe.
    private val tts: TextToSpeech = TextToSpeech(activity.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.getDefault())
            tts.setSpeechRate(rate)
            tts.setOnUtteranceProgressListener(listener)
            ready = true
            pending?.let { speak(it.text, it.id) }
            pending = null
        } else {
            emit(SpeechEvent.Error("", "TTS init failed: status=$status"))
        }
    }

    override fun speak(text: String, utteranceId: String) {
        if (!ready) {
            // Buffer the one utterance requested before the engine finished initialising.
            pending = Utterance(text, utteranceId)
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
    }

    override fun stop() {
        pending = null
        tts.stop()
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        if (ready) tts.setSpeechRate(rate)
    }

    override fun availableVoices(): List<Voice> {
        if (!ready) return emptyList()
        val voices = tts.voices ?: return emptyList()
        return voices
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .map { v ->
                val loc = v.locale
                val display = buildString {
                    append(loc.displayLanguage)
                    if (loc.displayCountry.isNotBlank()) append(" (").append(loc.displayCountry).append(')')
                }.ifBlank { v.name }
                Voice(id = v.name, name = display, language = loc.toLanguageTag())
            }
            .sortedBy { it.name }
    }

    override fun setVoice(id: String) {
        if (!ready) return
        tts.voices?.firstOrNull { it.name == id }?.let { tts.voice = it }
    }

    override fun isSpeaking(): Boolean = tts.isSpeaking

    override fun shutdown() {
        pending = null
        tts.stop()
        tts.shutdown()
    }
}
