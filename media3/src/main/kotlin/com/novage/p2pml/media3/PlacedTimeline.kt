package com.novage.p2pml.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.source.ForwardingTimeline
import com.novage.p2pml.core.LiveDelay

/**
 * A live timeline with the core's placement, as the web adapters place their players: the window's
 * target live offset is [placement]'s delay, and its default position, where the player starts and
 * where `seekToDefaultPosition` goes, is that far behind the live edge, at the start of its
 * segment, as Media3 places its own.
 *
 * A window keeps Media3's placement when the app gave the item a target offset of its own, as the
 * HLS.js adapter leaves an app's live sync alone, and when the playlist gives its start
 * (`EXT-X-START`).
 */
@OptIn(UnstableApi::class)
internal class PlacedTimeline(timeline: Timeline, private val placement: LiveDelay) :
    ForwardingTimeline(timeline) {
    // With a program date time, Media3 measures the live offset from now, not from the end of the
    // playlist: the offset of each window's edge from now, read once, as Media3 reads its own when
    // it builds a timeline, so that each read of a window is the same (Timeline.equals). A stream
    // clock ahead of the device's makes it negative, as in Media3.
    private val edgeMs =
        LongArray(timeline.windowCount) { index ->
            val window = timeline.getWindow(index, Window())
            if (window.windowStartTimeMs == C.TIME_UNSET) 0L
            else window.currentUnixTimeMs - window.windowStartTimeMs - window.durationMs
        }

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
        val live = window.liveConfiguration
        if (live != null && ownsDelay(window)) {
            val playlist = (window.manifest as? HlsManifest)?.mediaPlaylist
            place(window, live, playlist, defaultPositionProjectionUs, edgeMs[windowIndex])
        }
        return window
    }

    private fun place(
        window: Window,
        live: MediaItem.LiveConfiguration,
        playlist: HlsMediaPlaylist?,
        defaultPositionProjectionUs: Long,
        edgeMs: Long,
    ) {
        val delayUs = (placement.delay * C.MICROS_PER_SECOND).toLong()
        val delayedUs = (window.durationUs - delayUs).coerceIn(0, window.durationUs)
        val startUs = startOfSegment(playlist?.segments.orEmpty(), delayedUs)
        // Projected as SinglePeriodTimeline projects its own default position.
        val projectedUs = startUs + defaultPositionProjectionUs
        window.defaultPositionUs =
            when {
                !window.isDynamic || defaultPositionProjectionUs == 0L -> startUs
                projectedUs > window.durationUs -> C.TIME_UNSET
                else -> projectedUs
            }
        window.liveConfiguration =
            live.buildUpon().setTargetOffsetMs(Util.usToMs(delayUs) + edgeMs).build()
    }

    internal companion object {
        /**
         * Whether the engine places live [window], and so owns its delay: not where the app gave
         * the item a target offset of its own, or the playlist gives its start (`EXT-X-START`).
         */
        fun ownsDelay(window: Window): Boolean =
            window.liveConfiguration != null &&
                window.durationUs != C.TIME_UNSET &&
                window.mediaItem.liveConfiguration.targetOffsetMs == C.TIME_UNSET &&
                (window.manifest as? HlsManifest)?.mediaPlaylist?.startOffsetUs.let {
                    it == null || it == C.TIME_UNSET
                }

        /**
         * The start of the segment of [positionUs], as Media3 starts a live HLS window, but never
         * the first segment of a longer window: the core places the player one segment inside the
         * window's tail, and the first segment is the one that the next reload removes. With
         * segments of different lengths, the position can fall in the first one.
         */
        fun startOfSegment(segments: List<HlsMediaPlaylist.Segment>, positionUs: Long): Long {
            if (segments.isEmpty()) return positionUs
            val index = Util.binarySearchFloor(segments, positionUs, true, true)
            return segments[index.coerceAtLeast(minOf(1, segments.lastIndex))].relativeStartTimeUs
        }
    }
}
