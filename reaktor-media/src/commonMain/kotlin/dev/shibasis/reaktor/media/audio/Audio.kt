package dev.shibasis.reaktor.media.audio

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RecordedAudio(val bytes: ByteArray, val mimeType: String, val durationMillis: Long)

enum class RecordStart { Success, PermissionFailure, RecorderFailure, ControllerFailure }

abstract class AudioRecorderAdapter<Controller>(controller: Controller) : Adapter<Controller>(controller) {
    protected val recordingState = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = recordingState.asStateFlow()

    protected val levelState = MutableStateFlow(0f)
    val level: StateFlow<Float> = levelState.asStateFlow()

    abstract suspend fun start(): RecordStart

    abstract suspend fun stop(): RecordedAudio?

    abstract suspend fun cancel()
}

var Feature.AudioRecorder by CreateSlot<AudioRecorderAdapter<*>>()

sealed interface Playback {
    data object Idle : Playback

    data class Active(val key: String, val positionMillis: Long, val durationMillis: Long, val playing: Boolean) : Playback
}

abstract class AudioPlayerAdapter<Controller>(controller: Controller) : Adapter<Controller>(controller) {
    protected val playbackState = MutableStateFlow<Playback>(Playback.Idle)
    val playback: StateFlow<Playback> = playbackState.asStateFlow()

    abstract suspend fun play(key: String, bytes: ByteArray, fromMillis: Long = 0)

    abstract fun pause()

    abstract fun resume()

    abstract fun stop()
}

var Feature.AudioPlayer by CreateSlot<AudioPlayerAdapter<*>>()
