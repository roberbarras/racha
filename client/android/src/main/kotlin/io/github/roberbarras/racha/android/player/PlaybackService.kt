package io.github.roberbarras.racha.android.player

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps an audio-only player going with the screen off or the app in the background (H15). The player stays the
 * player screen's own (its view model owns it); this service only holds its media session, so Media3 runs it in the
 * foreground with a media notification and lock-screen controls while it plays, and lets it go when it stops.
 */
class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        current?.let { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = current

    /** Swiping the app away while paused ends it; while playing it keeps going (that is what audio only is for). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = current?.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    companion object {
        @Volatile
        private var current: MediaSession? = null

        /** Whether an audio-only session is being kept alive (device tests). */
        val active: Boolean get() = current != null

        fun start(context: Context, session: MediaSession) {
            current = session
            context.startService(Intent(context, PlaybackService::class.java))
        }

        fun stop(context: Context, session: MediaSession) {
            if (current !== session) return
            current = null
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
