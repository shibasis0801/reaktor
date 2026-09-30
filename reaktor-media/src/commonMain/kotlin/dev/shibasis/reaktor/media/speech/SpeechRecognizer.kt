package dev.shibasis.reaktor.media.speech

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * How one recognition session should run.
 *
 * [language] is a BCP-47 tag ("en-US"), or null for the device default. [preferOnDevice] asks the OS
 * to keep recognition on-device when it can (Android `EXTRA_PREFER_OFFLINE`, iOS
 * `requiresOnDeviceRecognition`) — quieter, private, and offline, at some accuracy cost. [partialResults]
 * turns the streaming [RecognitionEvent.Partial] on; leave it off for a one-shot command where only the
 * final transcription matters.
 */
data class RecognitionRequest(
    val language: String? = null,
    val preferOnDevice: Boolean = false,
    val partialResults: Boolean = true,
)

/**
 * Why a recognition session failed — a small closed set so callers branch on the kind instead of
 * parsing platform strings. Android's `SpeechRecognizer.ERROR_*` codes and iOS's `NSError`s map onto
 * these; [Unknown] carries the platform's own text for logging only, never for control flow.
 */
sealed interface RecognitionError {
    /** No usable recognizer here: the iOS Simulator (its mic engine aborts the process), a device with
     *  no recognition service, or the engine reporting itself unavailable. Callers fall back to typing. */
    data object Unavailable : RecognitionError
    /** Microphone or speech-recognition permission is not granted. */
    data object PermissionDenied : RecognitionError
    /** The session ran but matched no speech. */
    data object NoMatch : RecognitionError
    /** A network transport error (a cloud recognizer with no connection). */
    data object Network : RecognitionError
    /** The recognizer is busy with another session. */
    data object Busy : RecognitionError
    /** Listening timed out with nothing heard. */
    data object Timeout : RecognitionError
    /** Anything else; [detail] is the platform message, for logs only. */
    data class Unknown(val detail: String) : RecognitionError
}

/**
 * The lifecycle of one recognition session, streamed on [SpeechRecognizer.events]. When
 * [RecognitionRequest.partialResults] is on, [Partial]s arrive as the user speaks; a session ends with
 * exactly one [Result] or one [Error].
 */
sealed interface RecognitionEvent {
    /** A best-guess transcription so far; superseded by later partials or the final [Result]. */
    data class Partial(val text: String) : RecognitionEvent
    /** The final transcription for this session. */
    data class Result(val text: String) : RecognitionEvent
    /** The session failed, or matched nothing. */
    data class Error(val error: RecognitionError) : RecognitionEvent
}

/**
 * Push-to-talk speech recognition — the input half of reaktor-media/speech, the counterpart of
 * [SpeechSynthesizer]. Platform impls (Android `SpeechRecognizer`, Darwin `SFSpeechRecognizer`) drive
 * [events] as one utterance is recognised. Scope is a single session: one utterance, or until [stop].
 * Long-form / file transcription is deliberately out of scope. Register one via
 * `Feature.SpeechRecognizer = AndroidSpeechRecognizer(activity)` and release it with [shutdown].
 *
 * Full-duplex barge-in (pausing playback before listening, resuming after) is the caller's job, the same
 * split [SpeechSynthesizer] keeps: this class captures speech, it does not know about the reader.
 */
abstract class SpeechRecognizer<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller) {

    // Non-suspending buffer so engine callbacks (which fire on their own thread) publish without a
    // coroutine and without reordering. Matches SpeechSynthesizer's events stream.
    protected val _events = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 64)
    val events: Flow<RecognitionEvent> get() = _events

    protected fun emit(event: RecognitionEvent): Boolean = _events.tryEmit(event)

    /** Begin one listening session; results arrive on [events]. A no-op if already listening. */
    abstract fun start(request: RecognitionRequest = RecognitionRequest())

    /** Stop capturing and let the engine finalise — a last [RecognitionEvent.Result] may still arrive. */
    abstract fun stop()

    /** Abort the session immediately; no [RecognitionEvent] is delivered for it. */
    abstract fun cancel()

    abstract fun isListening(): Boolean

    /** Whether a recognizer can run here at all — false on the iOS Simulator and where no service exists.
     *  Callers check this before offering voice, and fall back to typed input when it is false. */
    abstract fun isAvailable(): Boolean

    /** Release the underlying engine. Call from the owning lifecycle's teardown. */
    abstract fun shutdown()
}

/**
 * An in-memory [SpeechRecognizer] for tests and for previews on targets with no engine — the recognizer
 * counterpart of `InMemoryPermissionAdapter`. It holds no platform resources; a test scripts a session
 * by calling [feedPartial] / [feedResult] / [feedError], and asserts on [events] and the listening flag.
 */
class InMemorySpeechRecognizer(
    private val available: Boolean = true,
) : SpeechRecognizer<Unit>(Unit) {

    private var listening = false

    /** The request the last [start] was given, for assertions. */
    var lastRequest: RecognitionRequest? = null
        private set

    override fun start(request: RecognitionRequest) {
        if (!available) {
            emit(RecognitionEvent.Error(RecognitionError.Unavailable))
            return
        }
        lastRequest = request
        listening = true
    }

    override fun stop() { listening = false }
    override fun cancel() { listening = false }
    override fun isListening(): Boolean = listening
    override fun isAvailable(): Boolean = available
    override fun shutdown() { listening = false }

    /** Simulate a streaming partial transcription. */
    fun feedPartial(text: String): Boolean = emit(RecognitionEvent.Partial(text))

    /** Simulate the final transcription; ends the session. */
    fun feedResult(text: String): Boolean {
        listening = false
        return emit(RecognitionEvent.Result(text))
    }

    /** Simulate a failed session. */
    fun feedError(error: RecognitionError): Boolean {
        listening = false
        return emit(RecognitionEvent.Error(error))
    }
}

var Feature.SpeechRecognizer by CreateSlot<SpeechRecognizer<*>>()
