package dev.shibasis.reaktor.media.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.adapters.PermissionAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AndroidAudioRecorder(
    activity: ComponentActivity,
    private val permissionAdapter: PermissionAdapter<*>,
) : AudioRecorderAdapter<ComponentActivity>(activity) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var meter: Job? = null

    override suspend fun start(): RecordStart {
        val activity = controller ?: return RecordStart.ControllerFailure
        if (!permissionAdapter.request(Permission.MICROPHONE)) return RecordStart.PermissionFailure
        cancel()
        val target = withContext(Dispatchers.IO) { File.createTempFile("voice", ".m4a", activity.cacheDir) }
        val started = runCatching {
            @Suppress("DEPRECATION")
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(activity) else MediaRecorder()
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(48_000)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
        }.onFailure { Logger.e(it) { "Audio recording could not start" } }.getOrNull()
        if (started == null) {
            target.delete()
            return RecordStart.RecorderFailure
        }
        recorder = started
        file = target
        startedAt = SystemClock.elapsedRealtime()
        recordingState.value = true
        meter = scope.launch {
            while (isActive) {
                levelState.value = ((recorder?.maxAmplitude ?: 0) / 32_767f).coerceIn(0f, 1f)
                delay(100)
            }
        }
        return RecordStart.Success
    }

    override suspend fun stop(): RecordedAudio? {
        val active = recorder ?: return null
        val target = file
        val duration = SystemClock.elapsedRealtime() - startedAt
        val stopped = runCatching { active.stop() }.isSuccess
        release()
        file = null
        val bytes = if (stopped && target != null) withContext(Dispatchers.IO) { runCatching { target.readBytes() }.getOrNull() } else null
        target?.delete()
        return bytes?.takeIf { it.isNotEmpty() }?.let { RecordedAudio(it, "audio/mp4", duration) }
    }

    override suspend fun cancel() {
        recorder?.let { runCatching { it.stop() } }
        release()
        file?.delete()
        file = null
    }

    private fun release() {
        meter?.cancel()
        meter = null
        recorder?.let { runCatching { it.release() } }
        recorder = null
        recordingState.value = false
        levelState.value = 0f
    }
}

class AndroidAudioPlayer(activity: ComponentActivity) : AudioPlayerAdapter<ComponentActivity>(activity) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var player: MediaPlayer? = null
    private var key: String? = null
    private var file: File? = null
    private var ticker: Job? = null

    override suspend fun play(key: String, bytes: ByteArray, fromMillis: Long) {
        val activity = controller ?: return
        stop()
        val target = withContext(Dispatchers.IO) { File.createTempFile("voice-play", ".m4a", activity.cacheDir).apply { writeBytes(bytes) } }
        val started = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(target.absolutePath)
                prepare()
                if (fromMillis > 0) seekTo(fromMillis.toInt())
                setOnCompletionListener { stop() }
                start()
            }
        }.onFailure { Logger.e(it) { "Audio playback could not start" } }.getOrNull()
        if (started == null) {
            target.delete()
            return
        }
        player = started
        this.key = key
        file = target
        tick()
    }

    override fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        ticker?.cancel()
        publish()
    }

    override fun resume() {
        player?.start()
        tick()
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        key = null
        file?.delete()
        file = null
        playbackState.value = Playback.Idle
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                publish()
                delay(100)
            }
        }
    }

    private fun publish() {
        val active = player ?: return
        val playing = key ?: return
        playbackState.value = Playback.Active(playing, active.currentPosition.toLong(), active.duration.toLong(), active.isPlaying)
    }
}
