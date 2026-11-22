package com.novage.p2pml.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.novage.p2pml.core.LiveDelay

/**
 * The player's load control: [inner]'s, with the forward buffer of a live player that the engine
 * placed held to the core's `playerBufferFor`, as the web adapters hold their players'. Peers fetch
 * the segments between that buffer and the live edge. [placement] gives the engine's placement, or
 * null when it has none. Where the app's target offset or `EXT-X-START` keeps Media3's placement,
 * the buffer is [inner]'s too: the limit is measured from the engine's delay, so it is the engine's
 * only where that delay is (specs/player-adapters.md of the web library, rule 1).
 */
@OptIn(UnstableApi::class)
internal class CoreLoadControl(
    private val inner: LoadControl,
    private val placement: () -> LiveDelay?,
) : LoadControl {
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // The inner control decides first, in every case, as without the limit.
        val wanted = inner.shouldContinueLoading(parameters)
        val limit = placement()?.playerBuffer
        if (limit == null || !ownsDelay(parameters)) return wanted
        return wanted && parameters.bufferedDurationUs < (limit * C.MICROS_PER_SECOND).toLong()
    }

    // Reused: Media3 asks on its playback thread only.
    private val period = Timeline.Period()
    private val window = Timeline.Window()

    // Whether the engine placed the window of the loading period.
    private fun ownsDelay(parameters: LoadControl.Parameters): Boolean {
        val timeline = parameters.timeline
        val index = timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (index == C.INDEX_UNSET) return false
        val windowIndex = timeline.getPeriod(index, period).windowIndex
        return PlacedTimeline.ownsDelay(timeline.getWindow(windowIndex, window))
    }

    // Kotlin's delegation does not forward Java default methods: each is forwarded here.

    override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = inner.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = inner.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        inner.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        inner.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean =
        inner.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        inner.shouldStartPlayback(parameters)
}
