package com.novage.p2pml.media3

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.novage.p2pml.core.PlaybackState

/**
 * Watches [player] for the core, as the web adapters watch their media element: it [report]s the
 * playback state on each change, after each loaded chunk and once a second, and tells [onStop] when
 * the app stops the player. Media3 gives the player's state on its application thread only, and has
 * no event when the buffer grows: Media3's own controls also read it on a timer.
 */
@OptIn(UnstableApi::class)
internal class PlayerWatch(
    private val player: ExoPlayer,
    private val report: (PlaybackState) -> Unit,
    onStop: () -> Unit,
) {
    private val handler = Handler(player.applicationLooper)

    private val tick =
        object : Runnable {
            override fun run() {
                reportNow()
                handler.postDelayed(this, REPORT_INTERVAL_MS)
            }
        }

    private val listener =
        object : AnalyticsListener {
            override fun onPlaybackStateChanged(
                eventTime: AnalyticsListener.EventTime,
                state: Int,
            ) {
                // An error also makes the player idle, and the app prepares it again: the core
                // stays for that recovery. A stop has no error. A stop inside another callback
                // comes after the calls that follow it: a prepare after it has readied the core.
                val stopped = player.playbackState == Player.STATE_IDLE
                if (state == Player.STATE_IDLE && stopped && player.playerError == null) onStop()
            }

            // With no item, the player has released its source: as a stop.
            override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) {
                if (player.currentTimeline.isEmpty) onStop()
            }

            override fun onEvents(player: Player, events: AnalyticsListener.Events) {
                if (STATE_EVENTS.any(events::contains)) reportNow()
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                // A loaded media chunk adds to the buffer; a playlist or a key does not.
                if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA) reportNow()
            }

            // A released player has no state to report.
            override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
                handler.removeCallbacks(tick)
            }
        }

    /** Starts the watch, on the player's thread, before the app's first call to the player. */
    fun start() {
        player.addAnalyticsListener(listener)
        tick.run()
    }

    /** Ends the watch, from any thread. */
    fun stop() {
        handler.post {
            handler.removeCallbacks(tick)
            player.removeAnalyticsListener(listener)
        }
    }

    private fun reportNow() {
        val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
        val rate = if (player.isPlaying) player.playbackParameters.speed.toDouble() else 0.0
        report(PlaybackState(ahead / C.MILLIS_PER_SECOND.toDouble(), rate))
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 1000L
        val STATE_EVENTS =
            intArrayOf(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
            )
    }
}
