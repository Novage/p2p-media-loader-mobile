package com.novage.p2pml

import com.novage.p2pml.engine.JsEngine
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

// The public Core end to end: the JNI glue, the engine's glue around the real core, and a local
// server. The rules of the glue are tested in native/test/js/player_test.js.
// One test for each rule of the core.
@Suppress("TooManyFunctions")
class CoreTest {
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
                            "/range.ts" ->
                                MockResponse.Builder()
                                    .code(PARTIAL)
                                    .addHeader("Content-Range", "bytes 0-999/5000")
                                    .body(Buffer().write(ByteArray(RANGE_BYTES)))
                                    .build()
                            else -> MockResponse.Builder().code(NOT_FOUND).build()
                        }
                }
            start()
        }
    private val lines = LinkedBlockingQueue<String>()
    private var engine: JsEngine? = null

    private val core =
        Core(testConfig()) {
            JsEngine("test-agent") { _, message -> lines.put(message) }.also { engine = it }
        }

    @AfterTest
    fun stop() {
        core.close()
        assertTrue(assertNotNull(engine).awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        server.close()
        assertEquals(emptyList(), lines.toList(), "no engine line")
    }

    private fun url(path: String) = server.url(path).toString()

    // As a player: the master and the media playlist as they came.
    private fun giveManifests(target: Core = core) {
        target.processManifest(url("/master.m3u8"), data = MASTER)
        target.processManifest(url("/media.m3u8"), data = MEDIA)
    }

    // Loads on another thread, so that the test can abort, destroy or interrupt; the load's
    // result or its exception, within the timeout.
    private fun loadInBackground(path: String): Pair<Thread, CompletableFuture<SegmentResponse>> {
        val result = CompletableFuture<SegmentResponse>()
        val loader = thread {
            runCatching { core.loadSegment(url(path)) }
                .fold({ result.complete(it) }, { result.completeExceptionally(it) })
        }
        return loader to result
    }

    private fun CompletableFuture<SegmentResponse>.failure(): Throwable =
        assertNotNull(
            assertFailsWith<ExecutionException> { get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.cause
        )

    @Test
    fun getConfigGivesTheGivenSettingsAndTheDefaults() {
        val config = core.getConfig()
        // As the JS core's: the stream settings of each stream type.
        val main = assertNotNull(config.mainStream)
        assertEquals(SWARM_ID, main.swarmId, "a quote crosses JSON")
        assertEquals(0.0, main.httpDownloadTimeWindow)
        assertEquals(emptyList(), main.announceTrackers)
        assertEquals(DEFAULT_HTTP_TRIES, main.httpErrorRetries, "a default")
        assertEquals(SWARM_ID, assertNotNull(config.secondaryStream).swarmId)
    }

    @Test
    fun aSegmentComesThroughTheCore() {
        giveManifests()
        assertTrue(core.isSegmentLoadable(url("/seg0.ts")))
        val response = core.loadSegment(url("/seg0.ts"))
        assertContentEquals(segment, response.data)
        assertTrue(response.bandwidth > 0, "bandwidth ${response.bandwidth}")
    }

    @Test
    fun anUnknownUrlAndAnInitSegmentAreNotLoadable() {
        giveManifests()
        assertFalse(core.isSegmentLoadable(url("/other.ts")))
        assertFalse(core.isSegmentLoadable(url("/init.mp4")))
        // As in JS, a load of an unknown URL fails.
        assertIs<CoreRequestError>(loadInBackground("/other.ts").second.failure())
    }

    @Test
    fun aByteRangeSegmentIsFoundByItsRange() {
        giveManifests()
        assertFalse(core.isSegmentLoadable(url("/range.ts"), OTHER_RANGE))
        assertTrue(core.isSegmentLoadable(url("/range.ts"), RANGE))
        assertEquals(RANGE_BYTES, core.loadSegment(url("/range.ts"), RANGE).data.size)
    }

    @Test
    fun aServerErrorFailsTheLoad() {
        giveManifests()
        // After the core's own tries.
        val error = assertIs<CoreRequestError>(loadInBackground("/seg1.ts").second.failure())
        assertEquals("failed", error.type)
    }

    @Test
    fun abortSegmentLoadingAbortsTheLoad() {
        giveManifests()
        val (_, result) = loadInBackground("/seg2.ts")
        awaitTheRequest("/seg2.ts")
        core.abortSegmentLoading(url("/seg2.ts"))
        assertEquals("aborted", assertIs<CoreRequestError>(result.failure()).type)
    }

    @Test
    fun anInterruptAbortsTheLoad() {
        giveManifests()
        // The flag is read on the loader thread: it is gone once that thread ends.
        val outcome = CompletableFuture<Pair<Result<SegmentResponse>, Boolean>>()
        val loader = thread {
            val result = runCatching { core.loadSegment(url("/seg2.ts")) }
            outcome.complete(result to Thread.currentThread().isInterrupted)
        }
        awaitTheRequest("/seg2.ts")
        loader.interrupt()
        val (result, interrupted) = outcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertIs<InterruptedIOException>(result.exceptionOrNull())
        assertTrue(interrupted, "the interrupt stays")
    }

    @Test
    fun destroyAbortsTheOpenLoadAndTheCoreTakesANewSource() {
        giveManifests()
        val (_, result) = loadInBackground("/seg2.ts")
        awaitTheRequest("/seg2.ts")
        core.destroy()
        assertEquals("aborted", assertIs<CoreRequestError>(result.failure()).type)
        assertFalse(core.isSegmentLoadable(url("/seg0.ts")), "no source")
        giveManifests()
        assertTrue(core.isSegmentLoadable(url("/seg0.ts")), "a new source")
    }

    @Test
    fun aCallThatJsDoesNotTakeFailsAtOnce() {
        giveManifests()
        // The core's isSegmentLoadable throws in this engine: the bridge's listener does not take
        // the query.
        val engine = assertNotNull(engine)
        engine.post {
            engine.evaluateScript(
                "import('p2p-media-loader-core').then(({ Core }) => {" +
                    " Core.prototype.isSegmentLoadable = () => { throw new Error('broken'); }; })",
                "patch.js",
            )
        }
        // Within the timeout: a call that the bridge loses would wait for good.
        val loadable =
            CompletableFuture.supplyAsync { core.isSegmentLoadable(url("/seg0.ts")) }
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertFalse(loadable)
        val line = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(line.startsWith("Uncaught Error: broken"), line)
    }

    @Test
    fun closeFailsTheOpenLoad() {
        giveManifests()
        val (_, result) = loadInBackground("/seg2.ts")
        awaitTheRequest("/seg2.ts")
        core.close()
        assertEquals("failed", assertIs<CoreRequestError>(result.failure()).type)
    }

    @Test
    fun afterCloseNothingIsLoadable() {
        giveManifests()
        core.close()
        assertFalse(core.isSegmentLoadable(url("/seg0.ts")))
        assertIs<CoreRequestError>(loadInBackground("/seg0.ts").second.failure())
        // The given settings, in getConfig's shape.
        assertEquals(SWARM_ID, assertNotNull(core.getConfig().mainStream).swarmId)
    }

    @Test
    fun withNoNativeLibraryTheCoreLoadsNothing() {
        val config =
            testConfig().apply {
                segmentMemoryStorageLimit = MEMORY_MIB
                secondaryStream = StreamConfig().apply { swarmId = "other" }
            }
        val fallback = Core(config) { throw UnsatisfiedLinkError("no libp2pml") }
        giveManifests(fallback)
        assertFalse(fallback.isSegmentLoadable(url("/seg0.ts")))
        assertFailsWith<CoreRequestError> { fallback.loadSegment(url("/seg0.ts")) }
        // The given settings, in getConfig's shape: a stream type's own over the ones for both.
        val given = fallback.getConfig()
        assertEquals(MEMORY_MIB, given.segmentMemoryStorageLimit, "a common setting")
        assertEquals(null, given.swarmId, "no stream setting at the top")
        assertEquals(SWARM_ID, assertNotNull(given.mainStream).swarmId)
        assertEquals("other", assertNotNull(given.secondaryStream).swarmId)
        assertEquals(0.0, assertNotNull(given.secondaryStream).httpDownloadTimeWindow)
        fallback.close()
    }

    // Waits until the server has the request of [path], so that the load is open.
    private fun awaitTheRequest(path: String) {
        while (true) {
            val request = assertNotNull(server.takeRequest(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            if (request.url.encodedPath == path) return
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val SLOW_SECONDS = 60L
        const val SEGMENT_BYTES = 100_000
        const val RANGE_BYTES = 1000
        const val PATTERN = 251
        const val SERVER_ERROR = 500
        const val PARTIAL = 206
        const val NOT_FOUND = 404
        const val DEFAULT_HTTP_TRIES = 3
        const val SWARM_ID = "a\"b"
        const val MEMORY_MIB = 64.0
        // The range of range.ts in MEDIA, and another one.
        val RANGE = ByteRange(0, 999)
        val OTHER_RANGE = ByteRange(0, 499)
        const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n"
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
                "#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXTINF:4.0,\nseg2.ts\n" +
                "#EXTINF:4.0,\n#EXT-X-BYTERANGE:1000@0\nrange.ts\n#EXT-X-ENDLIST\n"

        // No prefetch: the core loads only the segments that the tests ask for. No tracker: the
        // tests need no network.
        fun testConfig() =
            CoreConfig().apply {
                httpDownloadTimeWindow = 0.0
                p2pDownloadTimeWindow = 0.0
                announceTrackers = emptyList()
                swarmId = SWARM_ID
            }
    }
}
