package dev.shibasis.reaktor.media.audio

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.adapters.PermissionAdapter
import dev.shibasis.reaktor.core.util.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
private fun speakerSession(): Boolean {
    val session = AVAudioSession.sharedInstance()
    return session.setCategory(AVAudioSessionCategoryPlayAndRecord, withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker, error = null) &&
        session.setActive(true, error = null)
}

@OptIn(ExperimentalForeignApi::class)
class DarwinAudioRecorder(private val permissionAdapter: PermissionAdapter<*>) : AudioRecorderAdapter<Unit>(Unit) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorder: AVAudioRecorder? = null
    private var url: NSURL? = null
    private var startedAt: TimeMark? = null
    private var meter: Job? = null

    override suspend fun start(): RecordStart {
        if (!permissionAdapter.request(Permission.MICROPHONE)) return RecordStart.PermissionFailure
        cancel()
        if (!speakerSession()) return RecordStart.RecorderFailure
        val target = NSURL.fileURLWithPath(NSTemporaryDirectory() + "voice-${NSUUID().UUIDString}.m4a")
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to NSNumber(unsignedInt = kAudioFormatMPEG4AAC),
            AVSampleRateKey to NSNumber(double = 44_100.0),
            AVNumberOfChannelsKey to NSNumber(int = 1),
            AVEncoderBitRateKey to NSNumber(int = 48_000),
        )
        val created = AVAudioRecorder(uRL = target, settings = settings, error = null)
        created.meteringEnabled = true
        if (!created.record()) {
            Logger.e { "Audio recording could not start" }
            return RecordStart.RecorderFailure
        }
        recorder = created
        url = target
        startedAt = TimeSource.Monotonic.markNow()
        recordingState.value = true
        meter = scope.launch {
            while (isActive) {
                recorder?.let {
                    it.updateMeters()
                    levelState.value = ((it.averagePowerForChannel(0u) + 60f) / 60f).coerceIn(0f, 1f)
                }
                delay(100)
            }
        }
        return RecordStart.Success
    }

    override suspend fun stop(): RecordedAudio? {
        val active = recorder ?: return null
        val duration = startedAt?.elapsedNow()?.inWholeMilliseconds ?: 0
        active.stop()
        release()
        val target = url
        url = null
        val bytes = target?.let { NSData.dataWithContentsOfURL(it) }?.toByteArray()
        target?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        return bytes?.takeIf { it.isNotEmpty() }?.let { RecordedAudio(it, "audio/mp4", duration) }
    }

    override suspend fun cancel() {
        recorder?.stop()
        release()
        url?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        url = null
    }

    private fun release() {
        meter?.cancel()
        meter = null
        recorder = null
        startedAt = null
        recordingState.value = false
        levelState.value = 0f
    }
}

@OptIn(ExperimentalForeignApi::class)
class DarwinAudioPlayer : AudioPlayerAdapter<Unit>(Unit) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var player: AVAudioPlayer? = null
    private var key: String? = null
    private var paused = false
    private var ticker: Job? = null

    override suspend fun play(key: String, bytes: ByteArray, fromMillis: Long) {
        stop()
        speakerSession()
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        val created = AVAudioPlayer(data = data, error = null)
        created.prepareToPlay()
        if (fromMillis > 0) created.currentTime = fromMillis / 1000.0
        if (!created.play()) {
            Logger.e { "Audio playback could not start" }
            return
        }
        player = created
        this.key = key
        paused = false
        tick()
    }

    override fun pause() {
        player?.pause()
        paused = true
        ticker?.cancel()
        publish()
    }

    override fun resume() {
        player?.play()
        paused = false
        tick()
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        player?.stop()
        player = null
        key = null
        paused = false
        playbackState.value = Playback.Idle
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val active = player ?: break
                if (!active.playing && !paused) {
                    stop()
                    break
                }
                publish()
                delay(100)
            }
        }
    }

    private fun publish() {
        val active = player ?: return
        val playing = key ?: return
        playbackState.value = Playback.Active(playing, (active.currentTime * 1000).toLong(), (active.duration * 1000).toLong(), active.playing)
    }
}
