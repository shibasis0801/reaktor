package dev.shibasis.reaktor.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import androidx.media3.common.MediaItem as Media3Item

/**
 * [AudioPlayer] on Media3: an ExoPlayer, and a MediaSession that puts it on the lock screen, in the
 * notification shade, on Bluetooth headphones and in the car.
 *
 * The player belongs to whoever built this — a long-lived node, not a service. Android still has
 * to be told a player exists, and that is all [ReaktorPlaybackService] does: it hands the system
 * this session. When playback starts, a controller is connected to bind that service, and Media3
 * promotes it to the foreground while sound is playing and lets it go when it stops.
 *
 * **Build it on the main thread, from the earliest point the process has** — `Application.onCreate`.
 * Media3 ties a player and its session to the thread that created them, and the system can start
 * the service from a headphone button with no Activity anywhere; the session has to exist by then.
 *
 * Speech settings throughout: the content type is speech, unplugging headphones pauses, and next
 * and previous skip by [skipForwardMs] and [skipBackMs] instead of changing item.
 *
 * @param sessionActivity opened when the notification is tapped. Defaults to the app's launcher
 *   entry, brought forward rather than started again.
 */
class AndroidAudioPlayer(
    context: Context,
    override val skipBackMs: Long = 15_000,
    override val skipForwardMs: Long = 30_000,
    sessionActivity: PendingIntent? = null,
) : AudioPlayer {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()

    override var resumption: (suspend () -> Resumption?)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var current: MediaItem? = null
    private var error: PlaybackError? = null
    private var controller: ListenableFuture<MediaController>? = null

    private val exo: ExoPlayer
    private val session: MediaSession

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
            scheduleTick()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) current?.let { _events.tryEmit(PlayerEvent.Ended(it.id)) }
        }

        override fun onPlayerError(failure: PlaybackException) {
            val reason = classify(failure)
            error = reason
            current?.let { _events.tryEmit(PlayerEvent.Failed(it.id, reason)) }
            publish()
        }
    }

    // While sound is playing the position moves without any event saying so; this reads it twice a
    // second for the scrubber. Nothing ticks while paused.
    private val tick = object : Runnable {
        override fun run() {
            publish()
            if (exo.isPlaying) main.postDelayed(this, TICK_MS)
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Build AndroidAudioPlayer on the main thread: Media3 ties a player and its session to " +
                "the thread that creates them."
        }
        exo = ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU and Wi-Fi awake while streaming with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(skipBackMs)
            .setSeekForwardIncrementMs(skipForwardMs)
            .build()
        exo.addListener(listener)

        session = MediaSession.Builder(app, SkipInsteadOfChangingItem(exo))
            .setId(SESSION_ID)
            .setCallback(Resume())
            .setMediaButtonPreferences(listOf(skipButton(back = true), skipButton(back = false)))
            .apply { (sessionActivity ?: launcher())?.let(::setSessionActivity) }
            .build()
        PlaybackSessions.active = session
    }

    override fun load(item: MediaItem, startAtMs: Long, play: Boolean) = onMain {
        current = item
        error = null
        exo.setMediaItem(item.toMedia3(), startAtMs.coerceAtLeast(0))
        exo.prepare()
        exo.playWhenReady = play
        if (play) bindService()
        publish()
    }

    override fun play() = onMain {
        if (current == null) return@onMain
        error = null
        // After a failure the player is idle and must be prepared again; at the end, play means
        // from the top.
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.play()
        bindService()
    }

    override fun pause() = onMain { exo.pause() }

    override fun seekTo(positionMs: Long) = onMain {
        exo.seekTo(positionMs.coerceAtLeast(0))
        publish()
    }

    override fun skipForward() = onMain {
        exo.seekForward()
        publish()
    }

    override fun skipBack() = onMain {
        exo.seekBack()
        publish()
    }

    override fun setSpeed(speed: Float) = onMain {
        // Pitch is left at 1: at 1.5× a voice should still sound like the person.
        exo.setPlaybackSpeed(speed)
    }

    override fun stop() = onMain {
        exo.stop()
        exo.clearMediaItems()
        current = null
        error = null
        unbindService()
        publish()
    }

    override fun release() = onMain {
        scope.cancel()
        unbindService()
        main.removeCallbacks(tick)
        if (PlaybackSessions.active === session) PlaybackSessions.active = null
        session.release()
        exo.release()
    }

    /**
     * The system's "resume" — on the lock screen after the process died, or a media button with
     * nothing loaded — answered from [resumption]. The item is adopted as [current] before Media3
     * loads it, so the state reports the caller's own item, not an anonymous one.
     */
    private inner class Resume : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val answer = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val resume = try {
                    resumption?.invoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    null
                }
                if (resume == null) {
                    answer.setException(UnsupportedOperationException("Nothing to resume"))
                } else {
                    current = resume.item
                    error = null
                    answer.set(MediaSession.MediaItemsWithStartPosition(listOf(resume.item.toMedia3()), 0, resume.positionMs))
                }
            }
            return answer
        }
    }

    private fun publish() {
        val item = current
        _state.value = PlayerState(
            item = item,
            // A failed item is not playing, whatever ExoPlayer still intends: the screen has to
            // offer play, because play is what tries again.
            playing = item != null && exo.playWhenReady && exo.playbackState != Player.STATE_ENDED && error == null,
            buffering = exo.playbackState == Player.STATE_BUFFERING,
            positionMs = exo.currentPosition.coerceAtLeast(0),
            durationMs = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: item?.durationMs,
            speed = exo.playbackParameters.speed,
            error = error,
        )
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        if (exo.isPlaying) main.postDelayed(tick, TICK_MS)
    }

    /**
     * Connects a controller to [ReaktorPlaybackService], which binds it. Bound, with a session that
     * is playing, Media3 moves the service to the foreground and posts the media notification.
     */
    private fun bindService() {
        if (controller != null) return
        val token = SessionToken(app, ComponentName(app, ReaktorPlaybackService::class.java))
        controller = MediaController.Builder(app, token).buildAsync()
    }

    private fun unbindService() {
        controller?.let(MediaController::releaseFuture)
        controller = null
    }

    private fun skipButton(back: Boolean): CommandButton {
        val seconds = ((if (back) skipBackMs else skipForwardMs) / 1000).toInt()
        val icon = if (back) {
            when (seconds) {
                5 -> CommandButton.ICON_SKIP_BACK_5
                10 -> CommandButton.ICON_SKIP_BACK_10
                15 -> CommandButton.ICON_SKIP_BACK_15
                30 -> CommandButton.ICON_SKIP_BACK_30
                else -> CommandButton.ICON_SKIP_BACK
            }
        } else {
            when (seconds) {
                5 -> CommandButton.ICON_SKIP_FORWARD_5
                10 -> CommandButton.ICON_SKIP_FORWARD_10
                15 -> CommandButton.ICON_SKIP_FORWARD_15
                30 -> CommandButton.ICON_SKIP_FORWARD_30
                else -> CommandButton.ICON_SKIP_FORWARD
            }
        }
        return CommandButton.Builder(icon)
            .setDisplayName(if (back) "Back $seconds seconds" else "Forward $seconds seconds")
            .setPlayerCommand(if (back) Player.COMMAND_SEEK_BACK else Player.COMMAND_SEEK_FORWARD)
            .setSlots(if (back) CommandButton.SLOT_BACK else CommandButton.SLOT_FORWARD)
            .build()
    }

    private fun launcher(): PendingIntent? {
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        // Brings the existing task forward, as the launcher icon would, instead of stacking a copy.
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        return PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val SESSION_ID = "reaktor-playback"
        const val TICK_MS = 500L

        fun MediaItem.toMedia3(): Media3Item = Media3Item.Builder()
            .setMediaId(id)
            .setUri(if (uri.startsWith("/")) Uri.fromFile(File(uri)) else Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album.ifEmpty { null })
                    .setArtworkUri(artworkUri?.let(Uri::parse))
                    .setDurationMs(durationMs)
                    .build(),
            )
            .build()

        fun classify(failure: PlaybackException): PlaybackError {
            val kind = when (failure.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackError.Kind.NotFound
                in 2000..2999 -> PlaybackError.Kind.Network
                in 3000..4999 -> PlaybackError.Kind.Unsupported
                else -> PlaybackError.Kind.Unknown
            }
            return PlaybackError(kind, failure.errorCodeName)
        }
    }
}

/**
 * For long-form audio, next and previous mean skip, not change item.
 *
 * A headphone double-press, the car's next button and the shade's arrows all arrive as
 * next/previous. Turning them into seek forward and back here, at the session's player, covers every
 * one of those surfaces at once — the app's own buttons call skip directly and never pass through.
 */
private class SkipInsteadOfChangingItem(player: Player) : ForwardingPlayer(player) {
    override fun seekToNext() = seekForward()
    override fun seekToNextMediaItem() = seekForward()
    override fun seekToPrevious() = seekBack()
    override fun seekToPreviousMediaItem() = seekBack()

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*SKIPS).build()

    override fun isCommandAvailable(command: Int): Boolean = command in SKIPS || super.isCommandAvailable(command)

    private companion object {
        val SKIPS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}

/** The session the service hands the system. One player per process. */
internal object PlaybackSessions {
    @Volatile
    var active: MediaSession? = null
}
