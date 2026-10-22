package dev.shibasis.reaktor.playback

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * How the system finds the player. Declared in this module's manifest, so an app never writes one.
 *
 * It owns nothing: the player and its session belong to [AndroidAudioPlayer], built by the app
 * when its process starts. That ordering is what lets this service work when the system starts it
 * with no Activity — a headphone button, a car, a "resume" on the lock screen — because by the time
 * `onGetSession` runs, `Application.onCreate` has already built the player. An app that builds its
 * player later than that gets no session here, and the system's request is refused rather than
 * answered with a player that does not exist yet.
 */
class ReaktorPlaybackService : MediaSessionService() {

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        PlaybackSessions.active

    /**
     * Swiped out of recents. If something is playing it carries on — that is what a player is for;
     * if nothing is, there is no reason left for the service, or its notification, to stay.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }
}
