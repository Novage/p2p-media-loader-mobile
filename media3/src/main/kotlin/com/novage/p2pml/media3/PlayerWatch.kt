package com.novage.p2pml.media3

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.novage.p2pml.core.LiveDelay
import com.novage.p2pml.core.PlaybackState

/**
 * Watches [player] for the core, as the web adapters watch their media element: it [report]s the
 * playback state on each change, after each loaded chunk and once a second, and tells [onCurrent]
 * which item the core is for: the current item, or none when the app stops the player or clears its
 * items. Media3 gives the player's state on its application thread only, and has no event when the
 * buffer grows: Media3's own controls also read it on a timer.
 *
 * Once a second, and when the app plays, it also moves a live player that falls too far behind the
 * live edge, such as after a long pause, back to its [placement], as HLS.js does at its maximum
 * latency. Media3 plays a plain HLS stream at speed 1 and would stay behind. A live player that
 * fails because its next segment left the window starts again at its default position: its place,
 * when the core placed it.
 */
@OptIn(UnstableApi::class)
internal class PlayerWatch(
    private val player: ExoPlayer,
    private val report: (PlaybackState) -> Unit,
    private val onCurrent: (MediaItem?) -> Unit,
    private val placement: () -> LiveDelay?,
) {
    private val handler = Handler(player.applicationLooper)
    private val checkNow = Runnable(::checkCurrent)

    private val tick =
        object : Runnable {
            override fun run() {
                reportNow()
                moveBackWhenBehind()
                handler.postDelayed(this, REPORT_INTERVAL_MS)
            }
        }

    private val listener =
        object : AnalyticsListener {
            override fun onPlaybackStateChanged(
                eventTime: AnalyticsListener.EventTime,
                state: Int,
            ) = checkCurrent()

            override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) =
                checkCurrent()

            override fun onMediaItemTransition(
                eventTime: AnalyticsListener.EventTime,
                mediaItem: MediaItem?,
                reason: Int,
            ) = checkCurrent()

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

            // At a play after a long pause, before the player loads from where it stopped: that
            // segment can be out of the live window, which fails the player.
            override fun onPlayWhenReadyChanged(
                eventTime: AnalyticsListener.EventTime,
                playWhenReady: Boolean,
                reason: Int,
            ) {
                if (playWhenReady) moveBackWhenBehind()
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException,
            ) = recoverFrom(error)

            // A released player has no state to report, and no item: the core leaves its swarm,
            // also after an error, which a released player never recovers from.
            override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
                handler.removeCallbacks(tick)
                onCurrent(null)
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
            handler.removeCallbacks(checkNow)
            player.removeAnalyticsListener(listener)
        }
    }

    /** Checks the current item again on the player's thread, from any thread: a source changed. */
    fun check() {
        handler.post(checkNow)
    }

    // The item that the core is for. A stop (idle with no error) and no item: none. An error also
    // makes the player idle, and the app prepares the player again, or, for BEHIND_LIVE_WINDOW,
    // this watch does: the current item keeps the core for that recovery, while Media3 has its
    // source released. The state is the player's now, not the event's: a stop inside another
    // callback comes after the calls that follow it.
    private fun checkCurrent() {
        val stopped = player.playbackState == Player.STATE_IDLE && player.playerError == null
        onCurrent(if (stopped) null else player.currentMediaItem)
    }

    /**
     * Starts a live player again at its default position when [error] is that its next segment left
     * the live window, as Media3's documentation recommends and as HLS.js recovers. A placed player
     * sits one segment inside the window's tail, where a stall can do that; a long pause can do it
     * to any live player.
     */
    internal fun recoverFrom(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
        }
    }

    private fun moveBackWhenBehind() {
        val placement = placement()
        if (placement == null || !player.playWhenReady || !player.isCurrentMediaItemLive) return
        // The duration of the current window, in ms.
        val windowMs = player.contentDuration
        if (windowMs == C.TIME_UNSET) return
        val msPerSecond = C.MILLIS_PER_SECOND.toDouble()
        val behind = (windowMs - player.currentPosition) / msPerSecond
        if (isTooFarBehind(behind, windowMs / msPerSecond, placement))
            player.seekToDefaultPosition()
    }

    private fun reportNow() {
        val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
        val rate = if (player.isPlaying) player.playbackParameters.speed.toDouble() else 0.0
        report(PlaybackState(ahead / C.MILLIS_PER_SECOND.toDouble(), rate))
    }

    internal companion object {
        /**
         * Whether a live player [behind] the live edge, in a [window], both in seconds, goes back
         * to its [placement], as the HLS.js adapter decides: past the core's `maxLiveLatencyFor`,
         * raised to the window, so that a viewer who went back in a wide (DVR) window stays there.
         */
        fun isTooFarBehind(behind: Double, window: Double, placement: LiveDelay): Boolean =
            behind > maxOf(placement.maxLiveLatency, window)

        private const val REPORT_INTERVAL_MS = 1000L
        private val STATE_EVENTS =
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
