package com.novage.p2pml.sample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.novage.p2pml.core.CoreConfig
import com.novage.p2pml.core.CoreEvent
import com.novage.p2pml.core.DownloadSource
import com.novage.p2pml.media3.Media3P2PEngine
import com.novage.p2pml.media3.Media3P2PEngineConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays the BBB live stream with Media3, through P2P Media Loader.
 *
 * For a test swarm (README.md, "The sample in a test swarm"), the intent's optional extras set the
 * swarm ID (`swarm`), the stream URL (`stream`), and a fixed rendition (`highest`): the one with
 * the highest bitrate that the phone's decoders support. Every 10 s the app logs the totals since
 * its last start: the bytes that the core loaded over HTTP and P2P, bytes uploaded, and the stalls;
 * and, at the time of the line, the connections that the core holds in all its swarms and the
 * height of the last video frame. A blank extra is the same as no extra.
 */
class MainActivity : Activity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var engine: Media3P2PEngine? = null
    private lateinit var totals: Totals
    private val handler = Handler(Looper.getMainLooper())

    private val logTotals =
        object : Runnable {
            override fun run() {
                logLine()
                handler.postDelayed(this, TOTALS_INTERVAL_MS)
            }
        }

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

            override fun onPlaybackStateChanged(playbackState: Int) {
                totals.playbackStateChanged(playbackState, player?.playWhenReady == true)
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
        val engine =
            Media3P2PEngine(
                Media3P2PEngineConfig(
                    CoreConfig().apply {
                        swarmId = intent.getStringExtra("swarm")?.ifBlank { null }
                    }
                )
            )
        this.engine = engine
        // The listeners keep this run's totals: late events of an old engine do not count.
        val totals = Totals().also { this.totals = it }
        engine.addEventListener(CoreEvent.OnChunkDownloaded) {
            (if (it.downloadSource == DownloadSource.HTTP) totals.http else totals.p2p).addAndGet(
                it.bytesLength
            )
        }
        engine.addEventListener(CoreEvent.OnChunkUploaded) {
            totals.uploaded.addAndGet(it.bytesLength)
        }
        engine.addEventListener(CoreEvent.OnPeerConnect) { totals.peers.incrementAndGet() }
        engine.addEventListener(CoreEvent.OnPeerClose) { totals.peers.decrementAndGet() }
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
                    if (intent.getBooleanExtra("highest", false)) {
                        // A rendition is a swarm: the other peers of a test play the same one,
                        // also on a phone whose screen is smaller than the top rendition. The
                        // selector skips a rendition that the decoders do not support: the line
                        // shows the height that plays.
                        it.trackSelectionParameters =
                            it.trackSelectionParameters
                                .buildUpon()
                                .clearViewportSizeConstraints()
                                .setForceHighestSupportedBitrate(true)
                                .build()
                    }
                    it.setMediaItem(
                        MediaItem.fromUri(
                            intent.getStringExtra("stream")?.ifBlank { null } ?: STREAM_URL
                        )
                    )
                    it.prepare()
                    it.play()
                }
        handler.postDelayed(logTotals, TOTALS_INTERVAL_MS)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(logTotals)
        logLine()
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

    // The height is 0 until the first video frame, then the height of the last frame.
    private fun logLine() = Log.i(TAG, "$totals height ${player?.videoSize?.height ?: 0}")

    /**
     * The totals of one run. The core's events come on the library's thread, and the player's on
     * the main thread.
     */
    private class Totals {
        val http = AtomicLong()
        val p2p = AtomicLong()
        val uploaded = AtomicLong()
        val peers = AtomicLong()
        private var stalls = 0
        private var state = Player.STATE_IDLE

        // A stall: the player had enough and now waits for data, while the viewer plays.
        fun playbackStateChanged(playbackState: Int, playWhenReady: Boolean) {
            if (
                state == Player.STATE_READY &&
                    playbackState == Player.STATE_BUFFERING &&
                    playWhenReady
            )
                stalls++
            state = playbackState
        }

        override fun toString() =
            "totals http ${http.get()} p2p ${p2p.get()} uploaded ${uploaded.get()} " +
                "peers ${peers.get()} stalls $stalls"
    }

    private companion object {
        const val TAG = "P2pmlSample"
        const val STREAM_URL = "https://live.novage.workers.dev/bbb/live.m3u8"
        const val TOTALS_INTERVAL_MS = 10_000L
    }
}
