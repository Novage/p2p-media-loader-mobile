package com.novage.p2pml.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.platform.app.InstrumentationRegistry
import com.novage.p2pml.core.Core
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

// The HLS data sources with the real core and a local server on the device. Upstream sends its own
// user agent, so the server tells the core's requests from upstream's.
@OptIn(UnstableApi::class)
class DataSourcesDeviceTest {
    private val segment = ByteArray(SEGMENT_BYTES) { (it % PATTERN).toByte() }
    private val requests = ConcurrentLinkedQueue<Request>()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.url.encodedPath
                        val agent = request.headers["User-Agent"]
                        requests += Request(path, request.url.encodedQuery, agent)
                        return when (path) {
                            "/hls/master.m3u8" -> MockResponse.Builder().body(MASTER).build()
                            "/hls/media.m3u8" -> MockResponse.Builder().body(MEDIA).build()
                            "/r/media.m3u8" ->
                                MockResponse.Builder()
                                    .code(FOUND)
                                    .addHeader("Location", "/hls/media.m3u8")
                                    .build()
                            "/hls/seg0.ts",
                            "/hls/init.mp4",
                            "/hls/other.ts" -> bytes(segment)
                            "/hls/seg1.ts" -> MockResponse.Builder().code(SERVER_ERROR).build()
                            // Only with what upstream sends, as with an app's own headers.
                            "/hls/auth.ts" ->
                                if (agent == UPSTREAM) bytes(segment)
                                else MockResponse.Builder().code(FORBIDDEN).build()
                            // The core waits for it longer than any test; upstream gets it.
                            "/hls/held.ts" ->
                                if (agent == UPSTREAM) bytes(segment)
                                else
                                    MockResponse.Builder()
                                        .headersDelay(SLOW_SECONDS, TimeUnit.SECONDS)
                                        .build()
                            "/hls/slow.m3u8" ->
                                MockResponse.Builder()
                                    .body(SLOW_MEDIA)
                                    .bodyDelay(BODY_DELAY_SECONDS, TimeUnit.SECONDS)
                                    .build()
                            // Longer than any test waits.
                            "/hls/seg2.ts" ->
                                MockResponse.Builder()
                                    .headersDelay(SLOW_SECONDS, TimeUnit.SECONDS)
                                    .build()
                            "/hls/range.ts" ->
                                MockResponse.Builder()
                                    .code(PARTIAL)
                                    .addHeader("Content-Range", "bytes 0-999/5000")
                                    .body(Buffer().write(segment.copyOf(RANGE_BYTES)))
                                    .build()
                            else -> MockResponse.Builder().code(NOT_FOUND).build()
                        }
                    }
                }
            start()
        }
    private val core = Core(testConfig())
    private val factory =
        P2pHlsDataSourceFactory(core, DefaultHttpDataSource.Factory().setUserAgent(UPSTREAM))

    @AfterTest
    fun stop() {
        core.close()
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun bytes(data: ByteArray) = MockResponse.Builder().body(Buffer().write(data)).build()

    // Reads [spec] through a data source of [dataType]: its bytes and its URI while it is open.
    private fun read(
        dataType: Int,
        spec: DataSpec,
        listener: TransferListener? = null,
    ): Pair<ByteArray, Uri?> {
        val source = factory.createDataSource(dataType)
        listener?.let(source::addTransferListener)
        try {
            source.open(spec)
            return DataSourceUtil.readToEnd(source) to source.uri
        } finally {
            source.close()
        }
    }

    private fun read(dataType: Int, path: String, listener: TransferListener? = null) =
        read(dataType, DataSpec(Uri.parse(url(path))), listener)

    private fun giveManifests() {
        read(C.DATA_TYPE_MANIFEST, "/hls/master.m3u8")
        read(C.DATA_TYPE_MANIFEST, "/hls/media.m3u8")
    }

    // The user agents of the requests of [path].
    private fun agentsOf(path: String) = requests.filter { it.path == path }.map { it.agent }

    // The core asked for [path] first, and upstream once, after it.
    private fun assertCoreThenUpstream(path: String) {
        val agents = agentsOf(path)
        assertTrue(agents.firstOrNull().let { it != null && it != UPSTREAM }, "$agents")
        assertEquals(listOf<String?>(UPSTREAM), agents.filter { it == UPSTREAM }, "$agents")
        assertEquals(UPSTREAM, agents.last(), "$agents")
    }

    @Test
    fun aPlaylistReachesTheCoreWithItsUrlAfterRedirects() {
        val (bytes, uri) = read(C.DATA_TYPE_MANIFEST, "/r/media.m3u8")
        assertEquals(MEDIA, String(bytes))
        assertEquals(url("/hls/media.m3u8"), uri.toString())
        // Its segments resolve against the URL after the redirect.
        assertTrue(core.isSegmentLoadable(url("/hls/seg0.ts")))
    }

    @Test
    fun aPlaylistIsAskedForWhole() {
        read(C.DATA_TYPE_MANIFEST, "/hls/media.m3u8?_HLS_msn=5&_HLS_skip=YES&a=b%20c")
        val queries = requests.filter { it.path == "/hls/media.m3u8" }.map { it.query }
        assertEquals(listOf<String?>("_HLS_msn=5&a=b%20c"), queries)
        assertTrue(core.isSegmentLoadable(url("/hls/seg0.ts")))
    }

    @Test
    fun aPlaylistOfACancelledLoadDoesNotReachTheCore() {
        val result = CompletableFuture<Result<Pair<ByteArray, Uri?>>>()
        val loader = thread {
            result.complete(runCatching { read(C.DATA_TYPE_MANIFEST, "/hls/slow.m3u8") })
        }
        awaitUntil("upstream requests /hls/slow.m3u8") { agentsOf("/hls/slow.m3u8").isNotEmpty() }
        loader.interrupt()
        result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertFalse(core.isSegmentLoadable(url("/hls/slow0.ts")))
    }

    @Test
    fun aLocalPlaylistDoesNotReachTheCore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val playlist = File(context.cacheDir, "local.m3u8").apply { writeText(MEDIA) }
        try {
            val source =
                P2pHlsDataSourceFactory(core, DefaultDataSource.Factory(context))
                    .createDataSource(C.DATA_TYPE_MANIFEST)
            try {
                source.open(DataSpec(Uri.fromFile(playlist)))
                assertEquals(MEDIA, String(DataSourceUtil.readToEnd(source)))
            } finally {
                source.close()
            }
            val segmentUrl = Uri.fromFile(File(context.cacheDir, "seg0.ts")).toString()
            assertFalse(core.isSegmentLoadable(segmentUrl))
        } finally {
            playlist.delete()
        }
    }

    @Test
    fun aKnownSegmentComesThroughTheCore() {
        giveManifests()
        val transfers = TransferCounter()
        val (bytes, uri) = read(C.DATA_TYPE_MEDIA, "/hls/seg0.ts", transfers)
        assertContentEquals(segment, bytes)
        assertEquals(url("/hls/seg0.ts"), uri.toString())
        assertFalse(UPSTREAM in agentsOf("/hls/seg0.ts"), "no request from upstream")
        assertEquals(listOf(1, 1, SEGMENT_BYTES, 1), transfers.counts())
    }

    @Test
    fun aByteRangeSegmentReachesTheCoreWithItsRange() {
        giveManifests()
        val spec = DataSpec(Uri.parse(url("/hls/range.ts")), 0, RANGE_BYTES.toLong())
        assertContentEquals(segment.copyOf(RANGE_BYTES), read(C.DATA_TYPE_MEDIA, spec).first)
        assertFalse(UPSTREAM in agentsOf("/hls/range.ts"), "no request from upstream")
    }

    @Test
    fun anInitSegmentAndAnUnknownSegmentGoUpstream() {
        giveManifests()
        for (path in listOf("/hls/init.mp4", "/hls/other.ts")) {
            assertContentEquals(segment, read(C.DATA_TYPE_MEDIA, path).first)
            assertEquals(listOf<String?>(UPSTREAM), agentsOf(path), path)
        }
    }

    @Test
    fun aSegmentThatTheCoreFailsComesFromUpstream() {
        giveManifests()
        val transfers = TransferCounter()
        assertContentEquals(segment, read(C.DATA_TYPE_MEDIA, "/hls/auth.ts", transfers).first)
        assertCoreThenUpstream("/hls/auth.ts")
        assertTrue(transfers.balanced(), "each transfer ends: ${transfers.counts()}")
        assertEquals(SEGMENT_BYTES, transfers.counts()[2])
    }

    @Test
    fun aSegmentThatTheCoreAbortsComesFromUpstream() {
        giveManifests()
        val held = CompletableFuture<Result<Pair<ByteArray, Uri?>>>()
        thread { held.complete(runCatching { read(C.DATA_TYPE_MEDIA, "/hls/held.ts") }) }
        awaitUntil("the core requests /hls/held.ts") { agentsOf("/hls/held.ts").isNotEmpty() }
        // A load of the same stream type, as of a second rendition: the core aborts the first.
        assertContentEquals(segment, read(C.DATA_TYPE_MEDIA, "/hls/seg0.ts").first)
        assertContentEquals(segment, held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow().first)
        assertCoreThenUpstream("/hls/held.ts")
    }

    @Test
    fun aSegmentThatFailsEverywhereFailsTheOpen() {
        giveManifests()
        val transfers = TransferCounter()
        assertFailsWith<HttpDataSource.InvalidResponseCodeException> {
            read(C.DATA_TYPE_MEDIA, "/hls/seg1.ts", transfers)
        }
        assertCoreThenUpstream("/hls/seg1.ts")
        assertTrue(transfers.balanced(), "each transfer ends: ${transfers.counts()}")
    }

    @Test
    fun anInterruptAbortsTheCoreLoad() {
        giveManifests()
        val result = CompletableFuture<Result<Pair<ByteArray, Uri?>>>()
        val loader = thread {
            result.complete(runCatching { read(C.DATA_TYPE_MEDIA, "/hls/seg2.ts") })
        }
        awaitUntil("the core requests /hls/seg2.ts") { agentsOf("/hls/seg2.ts").isNotEmpty() }
        loader.interrupt()
        val failure = result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionOrNull()
        assertIs<InterruptedIOException>(failure)
    }

    @Test
    fun otherRequestsGoUpstreamUntouched() {
        for (type in listOf(C.DATA_TYPE_DRM, C.DATA_TYPE_STEERING_MANIFEST, C.DATA_TYPE_UNKNOWN)) {
            assertIs<DefaultHttpDataSource>(factory.createDataSource(type), "type $type")
        }
    }

    // Counts a data source's reports: initializing, start, bytes, end.
    private class TransferCounter : TransferListener {
        private var initializing = 0
        private var start = 0
        private var bytes = 0
        private var end = 0

        fun counts() = listOf(initializing, start, bytes, end)

        fun balanced() = start == end

        override fun onTransferInitializing(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) {
            if (isNetwork) initializing++
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (isNetwork) start++
        }

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            if (isNetwork) bytes += bytesTransferred
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (isNetwork) end++
        }
    }

    private data class Request(val path: String, val query: String?, val agent: String?)

    private companion object {
        const val UPSTREAM = "upstream"
        const val BODY_DELAY_SECONDS = 1L
        const val FORBIDDEN = 403
        const val SLOW_SECONDS = 60L
        const val SEGMENT_BYTES = 100_000
        const val RANGE_BYTES = 1000
        const val PATTERN = 251
        const val FOUND = 302
        const val PARTIAL = 206
        const val SERVER_ERROR = 500
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
                "#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXTINF:4.0,\nseg2.ts\n" +
                "#EXTINF:4.0,\n#EXT-X-BYTERANGE:1000@0\nrange.ts\n#EXTINF:4.0,\nauth.ts\n" +
                "#EXTINF:4.0,\nheld.ts\n#EXT-X-ENDLIST\n"
        const val SLOW_MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4.0,\nslow0.ts\n#EXT-X-ENDLIST\n"
    }
}
