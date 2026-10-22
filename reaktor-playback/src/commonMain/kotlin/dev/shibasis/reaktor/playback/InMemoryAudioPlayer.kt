package dev.shibasis.reaktor.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A player with no sound and a clock the test moves, for exercising what sits above a player.
 *
 * Time passes only through [advance], so a test of "position is saved every fifteen seconds" takes
 * no fifteen seconds. Items load instantly with the duration they carry, or [defaultDurationMs].
 * [fail] makes the loaded item fail the way a dropped stream would.
 */
class InMemoryAudioPlayer(
    override val skipBackMs: Long = 15_000,
    override val skipForwardMs: Long = 30_000,
    private val defaultDurationMs: Long = 60 * 60_000,
) : AudioPlayer {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()

    /** Every call made, in order, for asserting what a caller asked for. */
    val calls = mutableListOf<String>()

    var released = false
        private set

    override fun load(item: MediaItem, startAtMs: Long, play: Boolean) {
        calls += "load ${item.id} at $startAtMs play=$play"
        val duration = item.durationMs ?: defaultDurationMs
        _state.value = PlayerState(
            item = item,
            playing = play,
            positionMs = startAtMs.coerceIn(0, duration),
            durationMs = duration,
            speed = _state.value.speed,
        )
    }

    override fun play() {
        calls += "play"
        _state.update { if (it.item == null) it else it.copy(playing = true, error = null) }
    }

    override fun pause() {
        calls += "pause"
        _state.update { it.copy(playing = false) }
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek $positionMs"
        _state.update { it.copy(positionMs = positionMs.coerceIn(0, it.durationMs ?: Long.MAX_VALUE)) }
    }

    override fun skipForward() = seekTo(_state.value.positionMs + skipForwardMs)

    override fun skipBack() = seekTo(_state.value.positionMs - skipBackMs)

    override fun setSpeed(speed: Float) {
        calls += "speed $speed"
        _state.update { it.copy(speed = speed) }
    }

    override fun stop() {
        calls += "stop"
        _state.value = PlayerState(speed = _state.value.speed)
    }

    override fun release() {
        calls += "release"
        released = true
        _state.value = PlayerState()
    }

    /** Lets [millis] of listening pass at the current speed, ending the item if it runs out. */
    fun advance(millis: Long) {
        val current = _state.value
        val item = current.item ?: return
        if (!current.playing) return
        val duration = current.durationMs ?: return
        val next = current.positionMs + (millis * current.speed).toLong()
        if (next >= duration) {
            _state.value = current.copy(positionMs = duration, playing = false)
            _events.tryEmit(PlayerEvent.Ended(item.id))
        } else {
            _state.value = current.copy(positionMs = next)
        }
    }

    /** Fails the loaded item, as a dropped connection or a missing file would. */
    fun fail(error: PlaybackError = PlaybackError(PlaybackError.Kind.Network)) {
        val item = _state.value.item ?: return
        _state.update { it.copy(playing = false, error = error) }
        _events.tryEmit(PlayerEvent.Failed(item.id, error))
    }
}
