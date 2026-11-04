package dev.shibasis.reaktor.media.speech

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 *
 * Every [speak] ends in Done or Error, even when the engine can't help it: a device with no engine, an
 * engine that refuses the utterance, and one that crashes or hangs on it (reporting nothing at all)
 * each surface as [SpeechEvent.Error], so a caller is never left waiting on a reading that won't come.
 */
class AndroidSpeechSynthesizer(
    activity: ComponentActivity,
) : SpeechSynthesizer<ComponentActivity>(activity) {

    @Volatile private var ready = false
    @Volatile private var failed = false
    private var rate = 1.0f
    private var voiceId: String? = null
    private var pending: Utterance? = null

    private data class Utterance(val text: String, val id: String)

    // The framework calls onStart once audio begins. An engine that crashes on an utterance (Google's
    // neural voices do, on some emulators) or hangs on it calls nothing, so a start that doesn't come
    // within START_TIMEOUT_MS is reported as an error instead.
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var waiting: Runnable? = null

    private fun watch(utteranceId: String) {
        unwatch()
        val check = Runnable {
            waiting = null
            emit(SpeechEvent.Error(utteranceId, "TTS engine did not start speaking"))
        }
        waiting = check
        main.postDelayed(check, START_TIMEOUT_MS)
    }

    private fun unwatch() {
        waiting?.let(main::removeCallbacks)
        waiting = null
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            unwatch()
            emit(SpeechEvent.Started(utteranceId.orEmpty()))
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val id = utteranceId.orEmpty()
            emit(SpeechEvent.Range(id, SpokenRange(id, start, end)))
        }

        override fun onDone(utteranceId: String?) {
            unwatch()
            emit(SpeechEvent.Done(utteranceId.orEmpty()))
        }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            unwatch()
            emit(SpeechEvent.Error(utteranceId.orEmpty(), "TTS error"))
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            unwatch()
            emit(SpeechEvent.Error(utteranceId.orEmpty(), "TTS error code=$errorCode"))
        }
    }

    // Init is async; the callback fires after construction, so referencing `tts` inside it is safe.
    private val tts: TextToSpeech = TextToSpeech(activity.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.getDefault())
            tts.setSpeechRate(rate)
            tts.setOnUtteranceProgressListener(listener)
            if (voiceId != null) applyVoice()
            ready = true
            pending?.let { speak(it.text, it.id) }
            pending = null
        } else {
            failed = true
            // An utterance buffered while the engine was starting fails with it.
            val id = pending?.id.orEmpty()
            pending = null
            emit(SpeechEvent.Error(id, "TTS init failed: status=$status"))
        }
    }

    override fun speak(text: String, utteranceId: String) {
        if (failed) {
            emit(SpeechEvent.Error(utteranceId, "TTS engine unavailable"))
            return
        }
        if (!ready) {
            // Buffer the one utterance requested before the engine finished initialising.
            pending = Utterance(text, utteranceId)
            return
        }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId) == TextToSpeech.ERROR) {
            emit(SpeechEvent.Error(utteranceId, "TTS engine refused the utterance"))
            return
        }
        watch(utteranceId)
    }

    override fun stop() {
        pending = null
        unwatch()
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
            .groupBy { languageName(it.locale).ifBlank { it.name } }
            .toSortedMap()
            .flatMap { (language, inLanguage) ->
                // Google's "en-US-language" is an alias for the language's default speaker, listed
                // beside the speakers themselves; keep it only when it is all a language has.
                val speakers = inLanguage.filterNot { it.features?.contains(LANGUAGE_ALIAS) == true }
                numbered(language, speakers.ifEmpty { inLanguage })
            }
    }

    // Engines name voices only by their locale, so every voice in a language would read "English
    // (United States)". Number the speakers instead. Google lists each speaker twice, on the device
    // and on its server ("en-us-x-iob-local", "en-us-x-iob-network"): one speaker, so one number,
    // told apart by needsNetwork, with the on-device copy first.
    private fun numbered(language: String, voices: List<android.speech.tts.Voice>): List<Voice> {
        val speakers = voices.map { speaker(it.name) }.distinct().sorted()
        return voices
            .sortedWith(compareBy({ speakers.indexOf(speaker(it.name)) }, { it.isNetworkConnectionRequired }))
            .map { v ->
                val number = speakers.indexOf(speaker(v.name)) + 1
                Voice(
                    id = v.name,
                    name = if (speakers.size == 1) language else "$language · Voice $number",
                    language = v.locale.toLanguageTag(),
                    needsNetwork = v.isNetworkConnectionRequired,
                )
            }
    }

    private fun speaker(voiceName: String) = voiceName.removeSuffix("-local").removeSuffix("-network")

    private fun languageName(locale: Locale) = buildString {
        append(locale.displayLanguage)
        if (locale.displayCountry.isNotBlank()) append(" (").append(locale.displayCountry).append(')')
    }

    override fun setVoice(id: String?) {
        // Kept even before the engine is ready: the init callback applies it.
        voiceId = id
        if (ready) applyVoice()
    }

    // The default is whatever setLanguage picks for the device's locale, the same voice init starts with.
    private fun applyVoice() {
        val id = voiceId
        if (id == null) tts.setLanguage(Locale.getDefault())
        else tts.voices?.firstOrNull { it.name == id }?.let { tts.voice = it }
    }

    override fun isSpeaking(): Boolean = tts.isSpeaking

    override fun shutdown() {
        pending = null
        unwatch()
        tts.stop()
        tts.shutdown()
    }
}

// Not a TextToSpeech.Engine constant: Google's engine sets it on its per-language alias voices.
private const val LANGUAGE_ALIAS = "legacySetLanguageVoice"

// Long enough for an engine to load a voice it hasn't used yet; short enough that a reader who
// pressed play isn't left wondering.
private const val START_TIMEOUT_MS = 6_000L
