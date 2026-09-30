package dev.shibasis.reaktor.media.speech

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.localeWithLocaleIdentifier
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionResult
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Darwin [SpeechRecognizer] over `SFSpeechRecognizer` fed by `AVAudioEngine`'s microphone tap. Needs
 * NSMicrophoneUsageDescription + NSSpeechRecognitionUsageDescription in the app's Info.plist; the first
 * [start] triggers the mic + speech-recognition prompts.
 *
 * Two behaviours are load-bearing, each learned the hard way:
 *  - **Simulator guard.** Starting the engine's mic on the iOS Simulator aborts the process inside
 *    CoreAudio (an AURemoteIO RPC timeout) — no Kotlin/ObjC catch can stop it. [isAvailable] is false
 *    there and [start] emits [RecognitionError.Unavailable] instead of touching the engine.
 *  - **Audio session.** Listening needs `playAndRecord` (record-only silences any playback). This saves
 *    the session's previous category/mode/options on [start] and restores them on stop, so a reader's
 *    text-to-speech is audible again afterwards — leaving the session in record, or deactivated, keeps
 *    the synthesizer silent for the rest of the session.
 *
 * Recognition callbacks fire off the main thread; engine/session work and emits are marshalled back to
 * the main queue. Register with `Feature.SpeechRecognizer = DarwinSpeechRecognizer()` and release with
 * [shutdown].
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinSpeechRecognizer : SpeechRecognizer<Unit>(Unit) {

    private val engine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var listening = false
    private var tapped = false

    // Saved audio-session state, restored on stop so playback (e.g. a reader's TTS) is audible again.
    private var savedCategory: String? = null
    private var savedMode: String? = null
    private var savedOptions: ULong = 0u

    private val isSimulator = NSProcessInfo.processInfo.environment["SIMULATOR_UDID"] != null

    override fun isAvailable(): Boolean = !isSimulator

    override fun isListening(): Boolean = listening

    override fun start(request: RecognitionRequest) {
        if (listening) return
        if (isSimulator) {
            emit(RecognitionEvent.Error(RecognitionError.Unavailable))
            return
        }
        SFSpeechRecognizer.requestAuthorization { status ->
            onMain {
                if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    emit(RecognitionEvent.Error(RecognitionError.PermissionDenied))
                } else {
                    startEngine(request)
                }
            }
        }
    }

    private fun startEngine(request: RecognitionRequest) {
        try {
            val rec = request.language
                ?.let { SFSpeechRecognizer(locale = NSLocale.localeWithLocaleIdentifier(it)) }
                ?: SFSpeechRecognizer()
            if (rec == null || !rec.available) {
                emit(RecognitionEvent.Error(RecognitionError.Unavailable))
                return
            }
            recognizer = rec

            val session = AVAudioSession.sharedInstance()
            savedCategory = session.category
            savedMode = session.mode
            savedOptions = session.categoryOptions
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                mode = session.mode,
                options = AVAudioSessionCategoryOptionDuckOthers or AVAudioSessionCategoryOptionDefaultToSpeaker,
                error = null,
            )
            session.setActive(true, error = null)

            val req = SFSpeechAudioBufferRecognitionRequest()
            req.shouldReportPartialResults = request.partialResults
            if (request.preferOnDevice && rec.supportsOnDeviceRecognition) req.requiresOnDeviceRecognition = true
            this.request = req

            val input = engine.inputNode
            val format = input.outputFormatForBus(0u)
            input.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
                buffer?.let { req.appendAudioPCMBuffer(it) }
            }
            tapped = true
            engine.prepare()
            engine.startAndReturnError(null)
            listening = true

            task = rec.recognitionTaskWithRequest(req) { result: SFSpeechRecognitionResult?, error ->
                onMain {
                    if (result != null) {
                        val text = result.bestTranscription.formattedString
                        if (result.final) {
                            emit(RecognitionEvent.Result(text))
                            teardown()
                        } else {
                            emit(RecognitionEvent.Partial(text))
                        }
                    } else if (error != null) {
                        emit(RecognitionEvent.Error(RecognitionError.Unknown(error.localizedDescription)))
                        teardown()
                    }
                }
            }
        } catch (t: Throwable) {
            emit(RecognitionEvent.Error(RecognitionError.Unknown("Couldn't start listening")))
            teardown()
        }
    }

    override fun stop() {
        request?.endAudio() // let the engine deliver a final result before capture ends
        stopCapture()
    }

    override fun cancel() {
        task?.cancel()
        teardown()
    }

    override fun shutdown() = teardown()

    private fun teardown() {
        task?.cancel()
        task = null
        request = null
        stopCapture()
    }

    private fun stopCapture() {
        if (engine.running) engine.stop()
        if (tapped) {
            engine.inputNode.removeTapOnBus(0u)
            tapped = false
        }
        listening = false
        // Restore the previous session so playback is audible again. (Record-only, or a deactivated
        // session, silences the synthesizer for the rest of the session.)
        val cat = savedCategory
        if (cat != null) {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(cat, mode = savedMode ?: session.mode, options = savedOptions, error = null)
            session.setActive(true, error = null)
            savedCategory = null
            savedMode = null
            savedOptions = 0u
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) { block() }
    }
}
