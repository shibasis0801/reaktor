package dev.shibasis.reaktor.media.speech

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFAudio.AVSpeechBoundary
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
    private val handler = Handler()

    init {
        engine.delegate = handler
    }

    override fun speak(text: String, utteranceId: String) {
        currentId = utteranceId
        if (engine.speaking) engine.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance(string = text)
        utterance.rate = (AVSpeechUtteranceDefaultSpeechRate * rate)
            .coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
        emit(SpeechEvent.Started(utteranceId))
        engine.speakUtterance(utterance)
    }

    override fun stop() {
        engine.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    override fun setRate(rate: Float) {
        this.rate = rate
    }

    override fun isSpeaking(): Boolean = engine.speaking

    override fun shutdown() {
        engine.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        engine.delegate = null
    }

    private inner class Handler : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            willSpeakRangeOfSpeechString: CValue<NSRange>,
            utterance: AVSpeechUtterance,
        ) {
            willSpeakRangeOfSpeechString.useContents {
                val start = location.toInt()
                emit(SpeechEvent.Range(currentId, SpokenRange(currentId, start, start + length.toInt())))
            }
        }

        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            emit(SpeechEvent.Done(currentId))
        }
    }
}
