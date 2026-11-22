package com.novage.p2pml.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import com.novage.p2pml.media3.PlacedTimeline.Companion.startOfSegment
import com.novage.p2pml.media3.PlayerWatch.Companion.isTooFarBehind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The core's placement of a live window, and the watch's rule to go back to it.
@OptIn(UnstableApi::class)
class PlacedTimelineTest {
    private fun Timeline.window(projectionUs: Long = 0) =
        getWindow(0, Timeline.Window(), projectionUs)

    @Test
    fun theWindowGetsTheCoresPlacement() {
        val window = PlacedTimeline(liveTimeline(), PLACEMENT).window()
        assertEquals(DELAY_MS, window.liveConfiguration?.targetOffsetMs, "the target offset")
        assertEquals(LIVE_WINDOW_US - DELAY_US, window.defaultPositionUs, "the start, at the delay")
    }

    @Test
    fun theStartIsProjectedAsMedia3ProjectsItsOwn() {
        val placed = PlacedTimeline(liveTimeline(), PLACEMENT)
        assertEquals(
            LIVE_WINDOW_US - DELAY_US + PROJECTION_US,
            placed.window(PROJECTION_US).defaultPositionUs,
        )
        assertEquals(
            C.TIME_UNSET,
            placed.window(LIVE_WINDOW_US).defaultPositionUs,
            "past the window",
        )
    }

    @Test
    fun withAProgramDateTimeTheOffsetIsFromNow() {
        // The playlist ended 5 s ago: Media3 measures the live offset from now.
        val start = System.currentTimeMillis() - Util.usToMs(LIVE_WINDOW_US) - EDGE_MS
        val offset = PlacedTimeline(liveTimeline(windowStartTimeMs = start), PLACEMENT).window()
        val target = offset.liveConfiguration?.targetOffsetMs ?: 0
        assertTrue(target in DELAY_MS + EDGE_MS..DELAY_MS + EDGE_MS + SLACK_MS, "target $target ms")
        assertEquals(LIVE_WINDOW_US - DELAY_US, offset.defaultPositionUs, "the start, at the delay")
    }

    @Test
    fun aStreamClockAheadOfTheDeviceGivesASmallerOffset() {
        // The playlist ends 5 s after now, by the device's clock: Media3 keeps the edge negative.
        val start = System.currentTimeMillis() - Util.usToMs(LIVE_WINDOW_US) + EDGE_MS
        val window = PlacedTimeline(liveTimeline(windowStartTimeMs = start), PLACEMENT).window()
        val target = window.liveConfiguration?.targetOffsetMs ?: 0
        assertTrue(target in DELAY_MS - EDGE_MS..DELAY_MS - EDGE_MS + SLACK_MS, "target $target ms")
    }

    @Test
    fun theStartIsAtASegmentButNeverTheFirstOne() {
        // Segments of 4.04, 3.98, 4.00 and 4.02 s: the window's average is 4.01 s.
        val segments =
            listOf(0L, 4_040_000L, 8_020_000L, 12_020_000L).mapIndexed { index, startUs ->
                HlsMediaPlaylist.Segment(
                    "seg$index.ts",
                    null,
                    "",
                    0,
                    0,
                    startUs,
                    null,
                    null,
                    null,
                    0,
                    C.LENGTH_UNSET.toLong(),
                    false,
                    emptyList(),
                )
            }
        // The core's delay puts the position one average segment in: in the first segment.
        assertEquals(4_040_000L, startOfSegment(segments, 4_010_000L), "the second segment")
        assertEquals(8_020_000L, startOfSegment(segments, 9_000_000L), "the start of its segment")
        assertEquals(0L, startOfSegment(segments.take(1), 1_000_000L), "a window of one segment")
        assertEquals(1_000_000L, startOfSegment(emptyList(), 1_000_000L), "no segments")
    }

    // As Media3's own live timeline: a timeline must read the same each time (Timeline.equals).
    @Test
    fun eachReadOfAWindowIsTheSame() {
        val start = System.currentTimeMillis() - Util.usToMs(LIVE_WINDOW_US) - EDGE_MS
        val placed = PlacedTimeline(liveTimeline(windowStartTimeMs = start), PLACEMENT)
        val first = placed.window()
        Thread.sleep(READ_GAP_MS)
        assertEquals(first, placed.window())
    }

    @Test
    fun anAppsOwnTargetOffsetStays() {
        val item = appOffsetItem()
        val window = PlacedTimeline(liveTimeline(item), PLACEMENT).window()
        assertEquals(Util.usToMs(MEDIA3_OFFSET_US), window.liveConfiguration?.targetOffsetMs)
        assertEquals(LIVE_WINDOW_US - MEDIA3_OFFSET_US, window.defaultPositionUs)
    }

    @Test
    fun aPlayerTooFarBehindGoesBack() {
        // Past the core's maxLiveLatency, a segment past the window's tail.
        assertFalse(isTooFarBehind(PLACEMENT.maxLiveLatency, WINDOW_S, PLACEMENT))
        assertTrue(isTooFarBehind(PLACEMENT.maxLiveLatency + 0.1, WINDOW_S, PLACEMENT))
        // In a wide (DVR) window, only past the window.
        assertFalse(isTooFarBehind(DVR_S, DVR_S, PLACEMENT))
        assertTrue(isTooFarBehind(DVR_S + 0.1, DVR_S, PLACEMENT))
    }

    private companion object {
        const val WINDOW_S = 40.0
        const val DELAY_MS = 36_000L
        const val DELAY_US = 36_000_000L
        const val PROJECTION_US = 2_000_000L
        const val EDGE_MS = 5_000L
        const val READ_GAP_MS = 20L
        const val SLACK_MS = 1_000L
        const val DVR_S = 300.0
    }
}
