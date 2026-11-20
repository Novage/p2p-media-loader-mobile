package com.novage.p2pml.media3

import com.novage.p2pml.core.CoreConfig
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

internal const val TIMEOUT_SECONDS = 10L
internal const val POLL_MS = 20L
internal const val NOT_FOUND = 404
internal const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n"

// No prefetch: the core loads only the segments that are asked for. No tracker: no peer joins.
internal fun testConfig() =
    CoreConfig().apply {
        httpDownloadTimeWindow = 0.0
        p2pDownloadTimeWindow = 0.0
        announceTrackers = emptyList()
        swarmId = "media3-test"
    }

// Waits until [condition] is true, for TIMEOUT_SECONDS at most.
internal fun awaitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (!condition()) {
        assertTrue(System.nanoTime() < deadline, what)
        Thread.sleep(POLL_MS)
    }
}
