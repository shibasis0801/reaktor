package dev.shibasis.reaktor.playback

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionRouteSharingPolicyLongFormAudio
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setRate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.AVAudioTimePitchAlgorithmTimeDomain
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorFileDoesNotExist
import platform.Foundation.timeIntervalSince1970
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPChangePlaybackRateCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeAudio
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIImage
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * [AudioPlayer] on AVPlayer, with the lock screen, Control Center, headphones and CarPlay's
 * transport wired to it.
 *
 * What Lector learned putting speech on the lock screen (`lector@59aea90`), kept and extended:
 *
 * - **The app needs `UIBackgroundModes` = `audio`** in its Info.plist, or iOS suspends it the
 *   moment the screen locks. That line is the app's; nothing here can add it.
 * - **A playback session in spoken-audio mode**, with long-form route sharing so AirPlay and the
 *   car treat it as a podcast. Activated on the first [load], not at launch — opening the app must
 *   not stop someone's music — and given back with notify-others on [stop], so their music resumes.
 * - **Interruptions pause, and resume when they end** if the system says to. Lector only paused.
 * - **Unplugging headphones pauses.**
 * - **Remote commands are registered once, and removed on [release].** Lector registered them
 *   again every time its screen was rebuilt.
 * - **Now-playing carries duration, elapsed time and the real rate.** Without them the lock-screen
 *   scrubber is wrong. They are published on changes, not every tick: the lock screen moves the
 *   scrubber itself from elapsed time and rate.
 *
 * Skip forward and back replace next and previous on the lock screen, with [skipForwardMs] and
 * [skipBackMs] as their intervals.
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinAudioPlayer(
    override val skipBackMs: Long = 15_000,
    override val skipForwardMs: Long = 30_000,
) : AudioPlayer {
    private val player = AVPlayer()
    private val scope: CoroutineScope = MainScope()

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()

    override var resumption: (suspend () -> Resumption?)? = null

    private var current: MediaItem? = null

    // The lock screen's artwork, decoded once per address.
    private var artwork: Pair<String, MPMediaItemArtwork>? = null
    private var error: PlaybackError? = null
    private var speed = 1f

    // Intent: play was asked for and not since undone. AVPlayer's own rate drops to zero while it
    // buffers, which is not the person pausing.
    private var wantsToPlay = false
    private var playAskedAt = 0.0
    private var resumeAfterInterruption = false
    private var sessionActive = false
    private var publishedDuration: Long? = null

    private var itemObservers = listOf<NSObjectProtocol>()
    private val sessionObservers: List<NSObjectProtocol>
    private val commandTargets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var ticker: Job? = null

    init {
        val center = NSNotificationCenter.defaultCenter
        sessionObservers = listOf(
            center.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { note ->
                onInterruption(note)
            },
            center.addObserverForName(AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue) { note ->
                val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
                // Headphones out: the sound would otherwise move to the speaker in a quiet room.
                if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) pause()
            },
        )
        registerRemoteCommands()
    }

    override fun load(item: MediaItem, startAtMs: Long, play: Boolean) = onMain {
        activateSession()
        val url = if (item.uri.startsWith("/")) NSURL.fileURLWithPath(item.uri) else NSURL.URLWithString(item.uri)
        if (url == null) {
            fail(item, PlaybackError(PlaybackError.Kind.NotFound, "Not an address: ${item.uri}"))
            return@onMain
        }
        current = item
        error = null
        publishedDuration = null
        val playerItem = AVPlayerItem(uRL = url)
        // Time-domain stretching is the one meant for speech: at 1.5× a voice keeps its pitch
        // without the warble the spectral algorithm gives it.
        playerItem.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmTimeDomain
        observe(playerItem)
        player.replaceCurrentItemWithPlayerItem(playerItem)
        if (startAtMs > 0) player.seekToTime(time(startAtMs))
        if (play) startPlaying() else wantsToPlay = false
        startTicking()
        publish()
        updateNowPlaying()
    }

    override fun play() = onMain {
        if (current == null) return@onMain
        activateSession()
        error = null
        startPlaying()
        publish()
        updateNowPlaying()
    }

    override fun pause() = onMain {
        wantsToPlay = false
        player.pause()
        publish()
        updateNowPlaying()
    }

    override fun seekTo(positionMs: Long) = onMain {
        if (current == null) return@onMain
        val target = positionMs.coerceIn(0, durationMs() ?: Long.MAX_VALUE)
        player.seekToTime(time(target)) { _ ->
            dispatch_async(dispatch_get_main_queue()) {
                publish()
                updateNowPlaying()
            }
        }
        publish(positionOverride = target)
    }

    override fun skipForward() = seekTo(positionMs() + skipForwardMs)

    override fun skipBack() = seekTo(positionMs() - skipBackMs)

    override fun setSpeed(speed: Float) = onMain {
        this.speed = speed
        if (wantsToPlay) player.setRate(speed)
        publish()
        updateNowPlaying()
    }

    override fun stop() = onMain {
        wantsToPlay = false
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        clearItemObservers()
        current = null
        error = null
        ticker?.cancel()
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        deactivateSession()
        publish()
    }

    override fun release() = onMain {
        stop()
        val center = NSNotificationCenter.defaultCenter
        sessionObservers.forEach { center.removeObserver(it) }
        commandTargets.forEach { (command, target) -> command.removeTarget(target) }
        commandTargets.clear()
        scope.cancel()
    }

    private fun startPlaying() {
        wantsToPlay = true
        playAskedAt = NSDate().timeIntervalSince1970
        player.play()
        player.setRate(speed)
    }

    private fun publish(positionOverride: Long? = null) {
        val item = current
        val status = player.timeControlStatus
        _state.value = PlayerState(
            item = item,
            playing = item != null && wantsToPlay,
            buffering = item != null && wantsToPlay && status == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
            positionMs = positionOverride ?: positionMs(),
            durationMs = durationMs() ?: item?.durationMs,
            speed = speed,
            error = error,
        )
    }

    /**
     * Twice a second while something is loaded: the position for the scrubber, a failure AVPlayer
     * reports only as a status, and a pause that did not come through here.
     */
    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val item = current ?: break
                val playerItem = player.currentItem
                if (playerItem != null && playerItem.status == AVPlayerItemStatusFailed && error == null) {
                    fail(item, classify(playerItem.error))
                    continue
                }
                // Paused by something other than us — Siri, another app taking the session — and
                // past the moment just after play() when AVPlayer has not started yet.
                val settled = NSDate().timeIntervalSince1970 - playAskedAt > 1.0
                if (wantsToPlay && settled && player.timeControlStatus == AVPlayerTimeControlStatusPaused && player.rate == 0f) {
                    wantsToPlay = false
                    updateNowPlaying()
                }
                val duration = durationMs()
                if (duration != publishedDuration) {
                    publishedDuration = duration
                    updateNowPlaying()
                }
                publish()
            }
        }
    }

    private fun observe(playerItem: AVPlayerItem) {
        clearItemObservers()
        val center = NSNotificationCenter.defaultCenter
        itemObservers = listOf(
            center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, playerItem, NSOperationQueue.mainQueue) { _ ->
                wantsToPlay = false
                publish()
                updateNowPlaying()
                current?.let { _events.tryEmit(PlayerEvent.Ended(it.id)) }
            },
            center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, playerItem, NSOperationQueue.mainQueue) { _ ->
                current?.let { fail(it, classify(playerItem.error)) }
            },
        )
    }

    private fun clearItemObservers() {
        val center = NSNotificationCenter.defaultCenter
        itemObservers.forEach { center.removeObserver(it) }
        itemObservers = emptyList()
    }

    private fun fail(item: MediaItem, reason: PlaybackError) {
        wantsToPlay = false
        player.pause()
        error = reason
        _events.tryEmit(PlayerEvent.Failed(item.id, reason))
        publish()
        updateNowPlaying()
    }

    private fun onInterruption(note: NSNotification?) {
        val info = note?.userInfo ?: return
        val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue
        if (type == AVAudioSessionInterruptionTypeBegan) {
            // A call. The system has already silenced us; remember whether we were playing.
            resumeAfterInterruption = wantsToPlay
            wantsToPlay = false
            publish()
            updateNowPlaying()
        } else {
            val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0u
            val shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL
            if (shouldResume && resumeAfterInterruption) play()
            resumeAfterInterruption = false
        }
    }

    private fun registerRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        fun on(command: MPRemoteCommand, handle: (MPRemoteCommandEvent?) -> Boolean) {
            command.enabled = true
            val target = command.addTargetWithHandler { event ->
                if (handle(event)) MPRemoteCommandHandlerStatusSuccess else MPRemoteCommandHandlerStatusCommandFailed
            }
            commandTargets += command to target
        }
        on(center.playCommand) { playOrResume() }
        on(center.pauseCommand) { pause(); true }
        on(center.togglePlayPauseCommand) { if (state.value.playing) { pause(); true } else playOrResume() }
        center.skipForwardCommand.preferredIntervals = listOf(skipForwardMs / 1000.0)
        center.skipBackwardCommand.preferredIntervals = listOf(skipBackMs / 1000.0)
        on(center.skipForwardCommand) { skipForward(); current != null }
        on(center.skipBackwardCommand) { skipBack(); current != null }
        on(center.changePlaybackPositionCommand) { event ->
            val position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: return@on false
            seekTo((position * 1000).toLong())
            true
        }
        center.changePlaybackRateCommand.supportedPlaybackRates = SUPPORTED_RATES
        on(center.changePlaybackRateCommand) { event ->
            val rate = (event as? MPChangePlaybackRateCommandEvent)?.playbackRate ?: return@on false
            setSpeed(rate)
            true
        }
        // Next and previous would show as track arrows; this is long-form audio, so the lock screen
        // shows the skip buttons instead.
        center.nextTrackCommand.enabled = false
        center.previousTrackCommand.enabled = false
    }

    /** Play, or — with nothing loaded, after a relaunch — ask the app what to resume. */
    private fun playOrResume(): Boolean {
        if (current != null) {
            play()
            return true
        }
        val ask = resumption ?: return false
        scope.launch {
            val resume = runCatching { ask() }.getOrNull() ?: return@launch
            load(resume.item, resume.positionMs, play = true)
        }
        return true
    }

    /** Fetches the artwork off the main thread, then puts it on the lock screen. */
    private fun loadArtwork(uri: String) {
        if (artwork?.first == uri) return
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val url = if (uri.startsWith("/")) NSURL.fileURLWithPath(uri) else NSURL.URLWithString(uri)
            val image = url?.let { NSData.dataWithContentsOfURL(it) }?.let { UIImage.imageWithData(it) }
                ?: return@dispatch_async
            val art = MPMediaItemArtwork(boundsSize = image.size) { _ -> image }
            dispatch_async(dispatch_get_main_queue()) {
                if (current?.artworkUri != uri) return@dispatch_async
                artwork = uri to art
                updateNowPlaying()
            }
        }
    }

    private fun updateNowPlaying() {
        val item = current ?: return
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to item.title,
            MPMediaItemPropertyArtist to item.artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to positionMs() / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to if (wantsToPlay) speed.toDouble() else 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate to 1.0,
            MPNowPlayingInfoPropertyMediaType to MPNowPlayingInfoMediaTypeAudio.toLong(),
        )
        if (item.album.isNotEmpty()) info[MPMediaItemPropertyAlbumTitle] = item.album
        item.artworkUri?.let { uri ->
            val art = artwork
            if (art != null && art.first == uri) info[MPMediaItemPropertyArtwork] = art.second else loadArtwork(uri)
        }
        (durationMs() ?: item.durationMs)?.let { info[MPMediaItemPropertyPlaybackDuration] = it / 1000.0 }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    private fun activateSession() {
        if (sessionActive) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayback,
            mode = AVAudioSessionModeSpokenAudio,
            routeSharingPolicy = AVAudioSessionRouteSharingPolicyLongFormAudio,
            options = 0u,
            error = null,
        )
        sessionActive = session.setActive(true, error = null)
    }

    private fun deactivateSession() {
        if (!sessionActive) return
        AVAudioSession.sharedInstance().setActive(
            false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null,
        )
        sessionActive = false
    }

    private fun positionMs(): Long {
        val seconds = CMTimeGetSeconds(player.currentTime())
        return if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else 0
    }

    private fun durationMs(): Long? {
        val duration = player.currentItem?.duration ?: return null
        val seconds = CMTimeGetSeconds(duration)
        return if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else null
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }

    private companion object {
        const val TICK_MS = 500L
        val SUPPORTED_RATES = listOf(0.8, 1.0, 1.2, 1.5, 1.8, 2.0)

        fun time(ms: Long) = CMTimeMakeWithSeconds(ms / 1000.0, 1000)

        fun classify(error: NSError?): PlaybackError {
            if (error == null) return PlaybackError(PlaybackError.Kind.Unknown)
            val kind = when {
                error.domain == NSURLErrorDomain && error.code == NSURLErrorFileDoesNotExist -> PlaybackError.Kind.NotFound
                error.domain == NSURLErrorDomain -> PlaybackError.Kind.Network
                error.domain == "AVFoundationErrorDomain" -> PlaybackError.Kind.Unsupported
                else -> PlaybackError.Kind.Unknown
            }
            return PlaybackError(kind, "${error.domain} ${error.code}: ${error.localizedDescription}")
        }
    }
}
