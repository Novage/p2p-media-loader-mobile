package com.novage.p2pml.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.novage.p2pml.media3.Media3P2PEngine

/** Plays the BBB live stream with Media3, through P2P Media Loader. */
class MainActivity : Activity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var engine: Media3P2PEngine? = null

    private val listener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // Media3's own wake-lock rule: on while the player plays or
                // stalls, so that a long stall shows instead of locking the
                // device; off when it is paused, has ended or has failed. Like
                // Media3's rule, it stays on while a transient loss of audio
                // focus holds playback.
                playerView.keepScreenOn =
                    player.playWhenReady &&
                        (player.playbackState == Player.STATE_BUFFERING ||
                            player.playbackState == Player.STATE_READY)
            }

            override fun onPlayerError(error: PlaybackException) {
                // The engine starts a live player that fell out of the window
                // again (BEHIND_LIVE_WINDOW).
                Log.w(TAG, "playback error: ${error.errorCodeName}", error)
            }
        }

    // setShowBuffering is one of Media3's unstable APIs.
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView =
            PlayerView(this).apply {
                // Android 15 and later draw the app under the system bars.
                fitsSystemWindows = true
                // A stall must show as one, not as a frozen frame.
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        setContentView(playerView)
    }

    override fun onStart() {
        super.onStart()
        val engine = Media3P2PEngine()
        this.engine = engine
        player =
            engine
                .buildPlayer(
                    this,
                    ExoPlayer.Builder(this)
                        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
                        .setHandleAudioBecomingNoisy(true),
                )
                .also {
                    it.addListener(listener)
                    playerView.player = it
                    it.setMediaItem(MediaItem.fromUri(STREAM_URL))
                    it.prepare()
                    it.play()
                }
    }

    override fun onStop() {
        super.onStop()
        // Removed first: clearing the surface and releasing the player still
        // deliver events, which would turn the screen-on flag back on.
        player?.removeListener(listener)
        playerView.keepScreenOn = false
        playerView.player = null
        player?.release()
        player = null
        // After the player, which loads through the engine until it is released.
        engine?.close()
        engine = null
    }

    private companion object {
        const val TAG = "P2pmlSample"
        const val STREAM_URL = "https://live.novage.workers.dev/bbb/live.m3u8"
    }
}
