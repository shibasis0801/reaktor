package dev.shibasis.reaktor.media.speech

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A half-open word range [start, end) into the utterance string handed to [SpeechSynthesizer.speak],
 * reported by the platform engine as it reaches each word.
 *
 * This callback is Lector's clock: it drives the reading highlight and the auto-scroll for free, off
 * the OS engine, with no cloud TTS and no per-character cost. See plans/lector.md §4.1.
 */
data class SpokenRange(
    val utteranceId: String,
    val start: Int,
    val end: Int,
)

/** Lifecycle of one spoken utterance, streamed on [SpeechSynthesizer.events]. */
sealed interface SpeechEvent {
    val utteranceId: String
    data class Started(override val utteranceId: String) : SpeechEvent
    data class Range(override val utteranceId: String, val range: SpokenRange) : SpeechEvent
    data class Done(override val utteranceId: String) : SpeechEvent
    data class Error(override val utteranceId: String, val message: String) : SpeechEvent
}

/**
 * Text-to-speech output. Platform impls (Android `TextToSpeech`, Darwin `AVSpeechSynthesizer`) drive
 * [events] as they speak; the word-boundary callback on that stream is what makes reading-follow
 * possible. Register one via `Feature.SpeechSynthesizer = AndroidSpeechSynthesizer(activity)`.
 *
 * Pause/resume are deliberately absent: Android's engine cannot pause mid-utterance, so the reading
 * loop implements pause as stop + re-speak-from-offset at a higher layer (Phase 1, barge-in).
 */
abstract class SpeechSynthesizer<Controller>(
    controller: Controller
) : Adapter<Controller>(controller) {

    // Non-suspending buffer so engine callbacks (which fire on the engine's own thread) can publish
    // without a coroutine and without reordering word ranges. 64 is far above word cadence.
    protected val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    val events: Flow<SpeechEvent> get() = _events

    protected fun emit(event: SpeechEvent): Boolean = _events.tryEmit(event)

    /** Speak [text]; word-boundary callbacks arrive on [events] tagged with [utteranceId]. */
    abstract fun speak(text: String, utteranceId: String = "0")

    /** Stop immediately and flush the queue. */
    abstract fun stop()

    /** 1.0 = normal cadence; engines accept roughly 0.5..4.0. */
    abstract fun setRate(rate: Float)

    abstract fun isSpeaking(): Boolean

    /** Release the underlying engine. Call from the owning lifecycle's teardown. */
    abstract fun shutdown()
}

var Feature.SpeechSynthesizer by CreateSlot<SpeechSynthesizer<*>>()
