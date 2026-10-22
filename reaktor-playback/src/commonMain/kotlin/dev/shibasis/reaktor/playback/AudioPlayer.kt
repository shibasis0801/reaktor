package dev.shibasis.reaktor.playback

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Something to play, described in strings and numbers so it crosses the common/platform line intact.
 *
 * [id] is the caller's own key and comes back unchanged in [PlayerState.item] and [PlayerEvent]s —
 * make it name the thing (an episode's key), not a slot ("the current one"), so an event that
 * arrives late is still about the right item.
 */
@Serializable
data class MediaItem(
    val id: String,
    /** `https://…`, `file://…`, or an absolute path to a downloaded file. */
    val uri: String,
    val title: String,
    /** Who made it — for a podcast, the show. The lock screen's second line. */
    val artist: String = "",
    val album: String = "",
    val artworkUri: String? = null,
    /** What the caller already believes the length is, used until the player has measured it. */
    val durationMs: Long? = null,
)

/** Why something would not play, in terms a screen can turn into words. */
@Serializable
data class PlaybackError(val kind: Kind, val detail: String = "") {
    enum class Kind {
        /** No connection, a timeout, or the server refused — trying again later may work. */
        Network,
        /** The address answered but there is nothing there, or a downloaded file has gone. */
        NotFound,
        /** The bytes arrived but are not audio this device can decode. */
        Unsupported,
        Unknown,
    }
}

/**
 * What the player is doing, as one value a screen can draw.
 *
 * [playing] is intent — play was asked for and not since undone — so a play button does not flicker
 * to "play" while the stream buffers. [buffering] says whether sound is actually coming out.
 */
data class PlayerState(
    val item: MediaItem? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val speed: Float = 1f,
    val error: PlaybackError? = null,
)

/** Where to pick up: an item and a position, for a player asked to resume with nothing loaded. */
data class Resumption(val item: MediaItem, val positionMs: Long)

/** Things that happen once, which a state would lose if two arrived between two reads. */
sealed interface PlayerEvent {
    val itemId: String

    /** Played to the end. The player stops there; what comes next is the caller's to decide. */
    data class Ended(override val itemId: String) : PlayerEvent

    data class Failed(override val itemId: String, val error: PlaybackError) : PlayerEvent
}

/**
 * Audio the operating system treats as a player.
 *
 * Not only sound out of a speaker: a session the lock screen, the notification shade, Control
 * Center, headphones, a watch and a car can all see and drive. Their commands arrive here as the
 * same calls the app makes — pausing from the lock screen changes [state] exactly as pausing from
 * a button does — so a screen only ever has one thing to observe.
 *
 * Next and previous on those surfaces skip by [skipForwardMs] and [skipBackMs] rather than change
 * item: this is a player for long-form audio, where a headphone double-press means "skip the ad".
 *
 * Every method may be called from any thread; implementations move the work to wherever their
 * platform requires it. Install one at process start — before anything restores a session — through
 * `Feature.AudioPlayer`. The slot stays empty on platforms without an implementation.
 */
interface AudioPlayer {
    val state: StateFlow<PlayerState>
    val events: Flow<PlayerEvent>

    val skipBackMs: Long
    val skipForwardMs: Long

    /**
     * What to play when the system asks to resume and nothing is loaded — Android's "resume" on the
     * lock screen after the process was killed, a headphone or AirPods play press. The app answers
     * from what it saved; null, or an answer of null, means there is nothing to resume.
     */
    var resumption: (suspend () -> Resumption?)?

    /** Replaces whatever is loaded. [startAtMs] is where to begin — a saved position. */
    fun load(item: MediaItem, startAtMs: Long = 0, play: Boolean = true)

    fun play()
    fun pause()
    fun toggle() = if (state.value.playing) pause() else play()

    fun seekTo(positionMs: Long)
    fun skipForward()
    fun skipBack()

    /** 1.0 is normal. Pitch is preserved: a voice at 1.5× still sounds like the person. */
    fun setSpeed(speed: Float)

    /** Unloads the item and takes the player off the lock screen. */
    fun stop()

    /** Frees the platform player. The instance cannot be used afterwards. */
    fun release()
}

var Feature.AudioPlayer by CreateSlot<AudioPlayer>()
