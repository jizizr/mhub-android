package io.github.mhub.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.mhub.app.MainActivity
import io.github.mhub.app.graph

/**
 * The system media session (notification, lock screen, Bluetooth, Android
 * Auto controls). Media3 keeps the service in the foreground while playing.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val activity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, graph.player.sessionPlayer).setSessionActivity(activity).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        graph.player.persistNow()
        // The player belongs to the application; only the session ends here.
        session?.release()
        session = null
        super.onDestroy()
    }
}

/**
 * ExoPlayer only ever holds the current song and the prefetched next one, so
 * next/previous/play from the notification are routed through the queue.
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(player: Player, private val controller: PlayerController) : ForwardingPlayer(player) {
    private val queueCommands = arrayOf(
        COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        COMMAND_PLAY_PAUSE,
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*queueCommands.toIntArray()).build()

    override fun isCommandAvailable(command: Int): Boolean = command in queueCommands || super.isCommandAvailable(command)

    /** While a silent placeholder holds the session, show the song being prepared. */
    override fun getMediaMetadata(): MediaMetadata = controller.holdMetadata ?: super.getMediaMetadata()

    override fun seekToNext() = controller.next()
    override fun seekToNextMediaItem() = controller.next()
    override fun seekToPrevious() = controller.previous()
    override fun seekToPreviousMediaItem() = controller.previous()
    override fun play() = controller.play()
    override fun pause() = controller.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) controller.play() else controller.pause()
    }

    private fun Array<Int>.toIntArray() = IntArray(size) { this[it] }
}
