package com.novage.p2pml.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.novage.p2pml.core.CoreConfig
import com.novage.p2pml.core.LiveDelay
import com.novage.p2pml.core.ProcessedManifest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

internal const val TIMEOUT_SECONDS = 10L
internal const val POLL_MS = 20L
internal const val NOT_FOUND = 404
internal const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n"
// The core's placement of 10 segments of 4 s.
internal val PLACEMENT = LiveDelay(36.0, 4.0, 32.0, 44.0)

internal const val LIVE_URL = "https://example.com/live.m3u8"
internal const val LIVE_WINDOW_US = 40_000_000L
internal const val MEDIA3_OFFSET_US = 12_000_000L

// A live window of 40 s, which Media3 placed 12 s behind its edge.
@OptIn(UnstableApi::class)
internal fun liveTimeline(
    item: MediaItem = MediaItem.fromUri(LIVE_URL),
    windowStartTimeMs: Long = C.TIME_UNSET,
): Timeline =
    SinglePeriodTimeline(
        C.TIME_UNSET,
        windowStartTimeMs,
        C.TIME_UNSET,
        C.TIME_UNSET,
        LIVE_WINDOW_US,
        0,
        LIVE_WINDOW_US - MEDIA3_OFFSET_US,
        true,
        true,
        false,
        null,
        item,
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(Util.usToMs(MEDIA3_OFFSET_US))
            .build(),
    )

// A live item with a target offset of the app's own.
internal fun appOffsetItem(): MediaItem =
    MediaItem.Builder()
        .setUri(LIVE_URL)
        .setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(APP_OFFSET_MS).build()
        )
        .build()

internal const val APP_OFFSET_MS = 20_000L

// No prefetch: the core loads only the segments that are asked for. No tracker: no peer joins.
internal fun testConfig() =
    CoreConfig().apply {
        httpDownloadTimeWindow = 0.0
        p2pDownloadTimeWindow = 0.0
        announceTrackers = emptyList()
        swarmId = "media3-test"
    }

// The link of a test's data sources: their media source has the core while [isServed]; it keeps
// the playlists that it gets.
internal class TestLink(@Volatile override var isServed: Boolean = true) : SourceLink {
    val kept = ConcurrentHashMap<String, Playlist>()

    override fun keep(key: String, playlist: Playlist) {
        kept[key] = playlist
    }

    override fun onManifest(manifest: ProcessedManifest) = Unit
}

// Waits until [condition] is true, for TIMEOUT_SECONDS at most.
internal fun awaitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (!condition()) {
        assertTrue(System.nanoTime() < deadline, what)
        Thread.sleep(POLL_MS)
    }
}
