package com.novage.p2pml.player

import com.novage.p2pml.engine.JsEngine
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

// The player bridge end to end: CoreSession, the JNI glue, the engine's glue around the real
// core, and a local server. The rules of the glue are tested in native/test/js/player_test.js.
class CoreSessionTest {
    private val segment = ByteArray(SEGMENT_BYTES) { (it % PATTERN).toByte() }
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/seg0.ts" ->
                                MockResponse.Builder().body(Buffer().write(segment)).build()
                            "/seg1.ts" -> MockResponse.Builder().code(SERVER_ERROR).build()
                            // Longer than any test waits.
                            "/seg2.ts" ->
                                MockResponse.Builder()
                                    .headersDelay(SLOW_SECONDS, TimeUnit.SECONDS)
                                    .build()
                            else -> MockResponse.Builder().code(NOT_FOUND).build()
                        }
                }
            start()
        }
    private val lines = LinkedBlockingQueue<String>()
    private val engine = JsEngine("test-agent") { _, message -> lines.put(message) }
    private val core = engine.player

    @AfterTest
    fun stop() {
        engine.close()
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        server.close()
        assertEquals(emptyList(), lines.toList(), "no engine line")
    }

    private fun url(path: String) = server.url(path).toString()

    // As a player: the core, then the master and the media playlist as they came.
    private fun startTheCore() {
        core.start(CONFIG)
        core.processManifest(url("/master.m3u8"), null, MASTER)
        core.processManifest(url("/media.m3u8"), null, MEDIA)
    }

    // The answer, or a failure after the timeout, so that a defect cannot hang the tests.
    private fun CoreSession.SegmentLoad.within(): SegmentResult =
        CompletableFuture.supplyAsync { await() }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    @Test
    fun aSegmentComesThroughTheCore() {
        startTheCore()
        val result = core.load(url("/seg0.ts"), null).within()
        assertContentEquals(segment, assertIs<SegmentResult.Data>(result).bytes)
    }

    @Test
    fun anUnknownUrlAndAnInitSegmentAreMisses() {
        startTheCore()
        assertEquals(SegmentResult.Miss, core.load(url("/other.ts"), null).within())
        assertEquals(SegmentResult.Miss, core.load(url("/init.mp4"), null).within())
    }

    @Test
    fun aLoadBeforeTheCoreStartsIsAMiss() {
        assertEquals(SegmentResult.Miss, core.load(url("/seg0.ts"), null).within())
    }

    @Test
    fun aServerErrorFailsTheLoad() {
        startTheCore()
        // After the core's own tries.
        val result = core.load(url("/seg1.ts"), null).within()
        assertEquals("failed", assertIs<SegmentResult.Failed>(result).message)
    }

    @Test
    fun anInterruptAbortsTheLoad() {
        startTheCore()
        val load = core.load(url("/seg2.ts"), null)
        var thrown: Throwable? = null
        var interrupted = false
        val waiter = thread {
            try {
                load.await()
            } catch (error: InterruptedIOException) {
                thrown = error
                interrupted = Thread.currentThread().isInterrupted
            }
        }
        waiter.interrupt()
        waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        assertIs<InterruptedIOException>(thrown)
        assertTrue(interrupted, "the interrupt stays set")
        assertEquals(SegmentResult.Failed("aborted"), load.within())
    }

    @Test
    fun theEngineCloseMakesAnOpenLoadAMiss() {
        startTheCore()
        val load = core.load(url("/seg2.ts"), null)
        engine.close()
        assertEquals(SegmentResult.Miss, load.within())
        assertEquals(SegmentResult.Miss, core.load(url("/seg0.ts"), null).within(), "after it")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val SLOW_SECONDS = 60L
        const val SEGMENT_BYTES = 100_000
        const val PATTERN = 251
        const val SERVER_ERROR = 500
        const val NOT_FOUND = 404
        // No prefetch: the core loads only the segments that the tests ask for. No tracker: the
        // tests need no network.
        const val CONFIG =
            """{"httpDownloadTimeWindow":0,"p2pDownloadTimeWindow":0,"announceTrackers":[]}"""
        const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n"
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
                "#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXTINF:4.0,\nseg2.ts\n" +
                "#EXT-X-ENDLIST\n"
    }
}
