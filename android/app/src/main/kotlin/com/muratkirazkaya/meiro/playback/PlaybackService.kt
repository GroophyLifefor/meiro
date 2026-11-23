package com.muratkirazkaya.meiro.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.muratkirazkaya.meiro.CHROME_UA
import com.muratkirazkaya.meiro.MainActivity
import com.muratkirazkaya.meiro.MeiroApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The app's playback service: a Media3 [MediaSessionService] around one ExoPlayer (HLS included), which gives the
 * media notification, the lock screen controls, audio focus and the pause when headphones are pulled out. The
 * queue lives in [PlaybackEngine]; the session's player is a [ForwardingPlayer] that sends next/previous to the
 * engine, because ExoPlayer itself holds only the track playing and the one preloaded after it.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var exo: ExoPlayer? = null
    @Volatile private var engine: PlaybackEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(CHROME_UA)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo = player
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, QueuePlayer(player)).setSessionActivity(open).build()
        scope.launch {
            val container = (application as MeiroApplication).container()
            val e = PlaybackEngine(
                this@PlaybackService, player, AudioResolver(this@PlaybackService, scope),
                container.playStore(), container.engine.deviceName, scope, PlaybackHost::publish,
                upNextSource = container.upNextSource, suggestions = container::suggestionPicks,
                autoPlayInitially = container.autoPlay(), saveAutoPlay = container::saveAutoPlay,
            )
            engine = e
            PlaybackHost.attach(e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        PlaybackHost.detach(engine)
        engine?.release(); engine = null
        session?.release(); session = null
        exo?.release(); exo = null
        scope.cancel()
        super.onDestroy()
    }

    /** What the system's controls see: a player with a next and a previous track whenever the queue has them. */
    private inner class QueuePlayer(p: Player) : ForwardingPlayer(p) {
        override fun getAvailableCommands(): Player.Commands {
            val e = engine ?: return super.getAvailableCommands()
            val b = super.getAvailableCommands().buildUpon()
            if (e.hasNext()) b.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) else b.removeAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if (e.hasPrevious()) b.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            return b.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        override fun hasNextMediaItem(): Boolean = engine?.hasNext() ?: super.hasNextMediaItem()
        override fun hasPreviousMediaItem(): Boolean = engine?.hasPrevious() ?: super.hasPreviousMediaItem()
        override fun seekToNext() { engine?.next() ?: super.seekToNext() }
        override fun seekToNextMediaItem() { engine?.next() ?: super.seekToNextMediaItem() }
        override fun seekToPrevious() { engine?.previous() ?: super.seekToPrevious() }
        override fun seekToPreviousMediaItem() { engine?.previous() ?: super.seekToPreviousMediaItem() }
        override fun play() { engine?.play() ?: super.play() }
        override fun pause() { engine?.pause() ?: super.pause() }
    }
}
