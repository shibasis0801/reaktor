package dev.shibasis.reaktor.media.speech

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.Foundation.NSRange
import platform.darwin.NSObject

/**
 * Darwin [SpeechSynthesizer] over `AVSpeechSynthesizer`. The delegate's `willSpeakRangeOfSpeechString`
 * reports the character range of each word as it is spoken (an `NSRange` into the utterance string) —
 * the same word-boundary signal the Android engine gives, so reading-follow works identically on
 * both platforms. Together with the Android impl this makes reaktor-media/speech real in both
 * directions (portfolio idea 10).
 *
 * `AVSpeechSynthesizer` has no delegate callback that fires *before* speech starts reliably across
 * versions, so Started is emitted from [speak] itself; only the word ranges and completion come from
 * the delegate. (The two ObjC "did…Utterance" callbacks also map to one Kotlin signature, so keeping
 * a single delegate method sidesteps that overload clash.)
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class DarwinSpeechSynthesizer : SpeechSynthesizer<Unit>(Unit) {

    private val engine = AVSpeechSynthesizer()
    private var currentId = "0"
    private var rate = 1.0f
    private var voiceId: String? = null
    private val handler = Handler()

    // The utterance currently being spoken. Delegate callbacks are matched against it so that events
    // for a superseded utterance are ignored: starting a new utterance (e.g. tap-to-read while already
    // reading) cancels the old one, and the simulator can deliver that old utterance's didFinish
    // asynchronously — emitting it as a Done would wrongly stop the new reading (the button would flip
    // back to "Read aloud"). AVSpeechUtterance uses identity equality, so `==` is a safe match.
    private var current: AVSpeechUtterance? = null

    init {
        engine.delegate = handler
    }

    override fun speak(text: String, utteranceId: String) {
        currentId = utteranceId
        if (engine.speaking) engine.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance(string = text)
        utterance.rate = (AVSpeechUtteranceDefaultSpeechRate * rate)
            .coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
        voiceId?.let { id -> AVSpeechSynthesisVoice.voiceWithIdentifier(id)?.let { utterance.voice = it } }
        current = utterance
        emit(SpeechEvent.Started(utteranceId))
        engine.speakUtterance(utterance)
    }

    override fun stop() {
        current = null // a later didFinish/didCancel for the stopped utterance must not emit Done
        engine.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    override fun setRate(rate: Float) {
        this.rate = rate
    }

    override fun availableVoices(): List<Voice> =
        AVSpeechSynthesisVoice.speechVoices().mapNotNull { v ->
            (v as? AVSpeechSynthesisVoice)?.let { Voice(it.identifier, it.name, it.language) }
        }

    override fun setVoice(id: String) {
        voiceId = id
    }

    override fun isSpeaking(): Boolean = engine.speaking

    override fun shutdown() {
        current = null
        engine.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        engine.delegate = null
    }

    private inner class Handler : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            willSpeakRangeOfSpeechString: CValue<NSRange>,
            utterance: AVSpeechUtterance,
        ) {
            if (utterance != current) return // a superseded utterance's ranges are stale
            willSpeakRangeOfSpeechString.useContents {
                val start = location.toInt()
                emit(SpeechEvent.Range(currentId, SpokenRange(currentId, start, start + length.toInt())))
            }
        }

        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            if (didFinishSpeechUtterance != current) return // ignore a superseded/cancelled utterance
            current = null
            emit(SpeechEvent.Done(currentId))
        }
    }
}
