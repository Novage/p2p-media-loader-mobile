package com.novage.p2pml.core

import com.novage.p2pml.engine.JsEngine
import java.io.InterruptedIOException
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

// The public Core end to end: the JNI glue, the engine's glue around the real core, and a local
// server. The rules of the glue are tested in native/test/js/player_test.js.
// One test for each rule of the core.
@Suppress("TooManyFunctions", "LargeClass")
class CoreTest {
    private val segment = ByteArray(SEGMENT_BYTES) { (it % PATTERN).toByte() }
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/seg0.ts",
                            "/audio0.ts" ->
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
                            // Only with the app's token, and the core's range.
                            "/signed.ts" ->
                                if (
                                    request.headers["X-Token"] == "1" &&
                                        request.headers["Range"] == "bytes=0-999"
                                ) {
                                    MockResponse.Builder()
                                        .code(PARTIAL)
                                        .addHeader("Content-Range", "bytes 0-999/5000")
                                        .body(Buffer().write(ByteArray(RANGE_BYTES)))
                                        .build()
                                } else {
                                    MockResponse.Builder().code(FORBIDDEN).build()
                                }
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
    fun theCoresEventsReachItsListenersOnTheEventThread() {
        val added = LinkedBlockingQueue<Pair<String, StreamAddedDetails>>()
        val loaded = LinkedBlockingQueue<SegmentLoadDetails>()
        val config = CompletableFuture<CoreConfig>()
        core.addEventListener(CoreEvent.OnStreamAdded) {
            added.put(Thread.currentThread().name to it)
        }
        core.addEventListener(CoreEvent.OnSegmentLoaded) { details ->
            // Not on the core's thread: a listener can call the core.
            config.complete(core.getConfig())
            loaded.put(details)
        }
        giveManifests()
        val (thread, stream) = assertNotNull(added.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("p2pml-events", thread)
        assertEquals(url("/media.m3u8"), stream.stream.runtimeId)
        assertEquals(StreamType.MAIN, stream.stream.type)
        core.loadSegment(url("/seg0.ts"))
        val details = assertNotNull(loaded.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(url("/seg0.ts"), details.segment.url)
        assertEquals(SEGMENT_BYTES.toLong(), details.bytesLength)
        assertEquals(DownloadSource.HTTP, details.downloadSource)
        val fromListener = config.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(SWARM_ID, fromListener.mainStream?.swarmId, "a call to the core in a listener")
    }

    @Test
    fun aRemovedListenerHasNoEventAndAThrowingOneStopsNoOther() {
        val after = LinkedBlockingQueue<StreamAddedDetails>()
        val removedCalls = AtomicInteger()
        val removed = CoreEventListener<StreamAddedDetails> { removedCalls.incrementAndGet() }
        // An Error, not only an Exception: an app's TODO() or assert.
        core.addEventListener(CoreEvent.OnStreamAdded) {
            throw AssertionError("a listener's failure")
        }
        // An app's exception whose text throws too.
        core.addEventListener(CoreEvent.OnStreamAdded) { throw TextlessException() }
        core.addEventListener(CoreEvent.OnStreamAdded, removed)
        core.addEventListener(CoreEvent.OnStreamAdded) { after.put(it) }
        core.removeEventListener(CoreEvent.OnStreamAdded, removed)
        giveManifests()
        assertNotNull(after.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the listener after it")
        assertEquals(0, removedCalls.get(), "the removed listener")
        // The failure is a line of the engine's log.
        val line = lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(line?.contains("a listener's failure") == true, "the line: $line")
        // With its class, where its text throws.
        val textless = lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(textless?.contains("TextlessException") == true, "the line: $textless")
    }

    private class TextlessException : RuntimeException() {
        override val message: String
            get() = error("no text")
    }

    @Test
    fun aCloseDoesNotInterruptARunningListener() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CompletableFuture<Boolean>()
        core.addEventListener(CoreEvent.OnStreamAdded) {
            running.countDown()
            interrupted.complete(
                try {
                    !release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    true
                }
            )
        }
        giveManifests()
        assertTrue(running.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the listener runs")
        core.close()
        release.countDown()
        assertEquals(false, interrupted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "interrupted")
    }

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
    fun theStreamsAndTheirSegmentsComeFromTheCore() {
        giveManifests()
        val stream = core.getStreams().single()
        assertEquals(url("/media.m3u8"), stream.runtimeId)
        assertEquals(StreamType.MAIN, stream.type)
        assertEquals(SWARM_ID, stream.swarmId)
        assertEquals(stream, core.getStream(url("/media.m3u8")))
        assertEquals(null, core.getStream(url("/other.m3u8")), "an unknown stream")
        assertTrue(core.hasSegment(url("/seg1.ts")))
        assertFalse(core.hasSegment(url("/other.ts")), "an unknown URL")
        assertTrue(core.hasSegment(url("/range.ts"), RANGE))
        assertFalse(core.hasSegment(url("/range.ts"), OTHER_RANGE), "another range")
    }

    @Test
    fun applyDynamicConfigChangesOnlyTheSettingsThatItSets() {
        core.applyDynamicConfig(
            DynamicCoreConfig().apply {
                isP2PDisabled = true
                mainStream = DynamicStreamConfig().apply { httpErrorRetries = MAIN_HTTP_TRIES }
            }
        )
        val config = core.getConfig()
        val main = assertNotNull(config.mainStream)
        val secondary = assertNotNull(config.secondaryStream)
        assertEquals(true, main.isP2PDisabled, "for both stream types")
        assertEquals(true, secondary.isP2PDisabled, "for both stream types")
        assertEquals(MAIN_HTTP_TRIES, main.httpErrorRetries, "for the main stream")
        assertEquals(DEFAULT_HTTP_TRIES, secondary.httpErrorRetries, "not for the secondary one")
        assertEquals(0.0, main.httpDownloadTimeWindow, "a setting that it does not set")
        assertEquals(SWARM_ID, main.swarmId)
    }

    @Test
    fun applyDynamicConfigDoesNotChangeTheSettingsThatTheCoreReadsOnce() {
        // A StreamConfig in place of a DynamicStreamConfig has them.
        val stream =
            StreamConfig().apply {
                isP2PUploadDisabled = true
                announceTrackers = listOf("wss://tracker.example.com")
                swarmId = "other"
            }
        core.applyDynamicConfig(DynamicCoreConfig().apply { mainStream = stream })
        val main = assertNotNull(core.getConfig().mainStream)
        assertEquals(true, main.isP2PUploadDisabled, "a dynamic setting")
        assertEquals(emptyList(), main.announceTrackers)
        assertEquals(SWARM_ID, main.swarmId)
    }

    // Runs [test] with a core of its own, made with [config], and closes it.
    private fun withCore(config: CoreConfig, test: (Core) -> Unit) {
        var ownEngine: JsEngine? = null
        Core(config) {
                JsEngine("test-agent") { _, message -> lines.put(message) }.also { ownEngine = it }
            }
            .use(test)
        // Its lines too, before stop() reads them.
        assertTrue(assertNotNull(ownEngine).awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun setManifestResponseUrlNamesTheSwarm() {
        // No configured swarmId, which would win.
        withCore(testConfig().apply { swarmId = null }) { named ->
            named.setManifestResponseUrl("https://cdn.example.com/show/index.m3u8?token=1")
            giveManifests(named)
            val stream = named.getStreams().single()
            assertEquals("https://cdn.example.com/show/index.m3u8", stream.swarmId)
        }
    }

    @Test
    fun functionsOfTheSettingsNameTheSwarmAndTheStreams() {
        val swarmContexts = LinkedBlockingQueue<SwarmIdContext>()
        val built = LinkedBlockingQueue<StreamIdBuilderContext>()
        // One function for both stream types: called once for each source.
        val swarm = SwarmIdFunction { swarmContexts.put(it).let { "show-42" } }
        val builder = StreamIdBuilder { context ->
            built.put(context)
            if (context.streamType == StreamType.MAIN) "video" else null
        }
        val config =
            testConfig().apply {
                swarmId = null
                streamIdBuilder = builder
                mainStream = StreamConfig().apply { swarmIdFunction = swarm }
                secondaryStream = StreamConfig().apply { swarmIdFunction = swarm }
            }
        withCore(config) { show ->
            val requested = url("/master.m3u8?id=42")
            fun giveShow() {
                show.processManifest(url("/master.m3u8"), requested, MASTER_WITH_AUDIO)
                show.processManifest(url("/media.m3u8"), data = MEDIA)
                show.processManifest(url("/audio.m3u8"), data = AUDIO)
            }
            giveShow()
            val (video, audio) = show.getStreams()
            assertEquals(listOf("show-42", "show-42"), listOf(video.swarmId, audio.swarmId))
            assertEquals(
                listOf(SwarmIdContext(url("/master.m3u8"), requested)),
                swarmContexts.toList(),
            )
            assertEquals("video", video.streamId)
            assertEquals("secondary0", audio.streamId, "null keeps the default")
            val context = built.first { it.streamType == StreamType.MAIN }
            assertEquals("show-42", context.swarmId)
            assertEquals(url("/media.m3u8"), context.runtimeId)
            assertEquals("main0", context.defaultStreamId)
            assertEquals(0, context.index)
            assertEquals(BANDWIDTH, context.properties.bitrate)
            val manifest = assertNotNull(context.manifest)
            assertEquals(requested, manifest.url)
            assertEquals(MASTER_WITH_AUDIO, manifest.text)
            assertEquals(
                listOf(StreamType.MAIN to "main0", StreamType.SECONDARY to "secondary0"),
                manifest.streams.map { it.streamType to it.defaultStreamId },
            )
            // The next source asks again.
            show.destroy()
            giveShow()
            assertEquals(2, swarmContexts.size)
            // getConfig gives the given functions back.
            val main = assertNotNull(show.getConfig().mainStream)
            assertSame(swarm, main.swarmIdFunction)
            assertSame(builder, main.streamIdBuilder)
        }
    }

    @Test
    fun aSegmentFilterKeepsSegmentsOffP2P() {
        val contexts = LinkedBlockingQueue<SegmentFilterContext>()
        val filter = SegmentFilter { context ->
            contexts.put(context)
            when {
                context.url.endsWith("/seg1.ts") -> false
                // An Error too.
                context.url.endsWith("/seg2.ts") -> TODO("the filter failed")
                else -> true
            }
        }
        withCore(testConfig().apply { segmentFilter = filter }) { filtered ->
            filtered.processManifest(url("/media.m3u8"), data = MEDIA_WITH_CUE)
            assertTrue(filtered.hasSegment(url("/seg0.ts")), "shared")
            assertFalse(filtered.hasSegment(url("/seg1.ts")), "kept off P2P")
            assertFalse(filtered.isSegmentLoadable(url("/seg1.ts")), "the player loads it")
            assertFalse(filtered.hasSegment(url("/seg2.ts")), "a throw keeps it off P2P")
            val cue = contexts.first { it.url == url("/seg1.ts") }
            assertEquals(StreamType.MAIN, cue.streamType)
            assertEquals(url("/media.m3u8"), cue.runtimeId)
            assertEquals(HlsSegmentMarkers(true, "30", null, null), cue.hls)
            val range = contexts.first { it.url == url("/range.ts") }
            assertEquals(RANGE, range.byteRange)
            assertEquals(
                LAST_START to LAST_START + SEGMENT_SECONDS,
                range.startTime to range.endTime,
            )
        }
    }

    @Test
    fun aSwarmIdFunctionThatThrowsFailsTheRegistration() {
        val calls = AtomicInteger()
        val config =
            testConfig().apply {
                swarmId = null
                swarmIdFunction = SwarmIdFunction {
                    if (calls.incrementAndGet() == 1) error("no id")
                    throw TextlessException()
                }
            }
        withCore(config) { failing ->
            val errors = LinkedBlockingQueue<StreamRegistrationErrorDetails>()
            failing.addEventListener(CoreEvent.OnStreamRegistrationError) { errors.put(it) }
            giveManifests(failing)
            assertEquals(emptyList(), failing.getStreams())
            val error = assertNotNull(errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).error
            assertEquals("no id", error.message, "the throw's text")
            // The next source: an exception whose text throws gives its class.
            failing.destroy()
            giveManifests(failing)
            assertEquals(emptyList(), failing.getStreams())
            val next = assertNotNull(errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).error
            assertEquals(TextlessException::class.java.name, next.message)
        }
    }

    @Test
    fun aSwarmIdFunctionFromJavaThatGivesNullSaysSo() {
        // As a Java function that returns null.
        val javaLike =
            Proxy.newProxyInstance(
                SwarmIdFunction::class.java.classLoader,
                arrayOf(SwarmIdFunction::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "swarmId" -> null
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "a Java-like SwarmIdFunction"
                }
            } as SwarmIdFunction
        val config =
            testConfig().apply {
                swarmId = null
                swarmIdFunction = javaLike
            }
        withCore(config) { failing ->
            val errors = LinkedBlockingQueue<StreamRegistrationErrorDetails>()
            failing.addEventListener(CoreEvent.OnStreamRegistrationError) { errors.put(it) }
            giveManifests(failing)
            val error = assertNotNull(errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).error
            assertEquals("swarmIdFunction gave null", error.message)
        }
    }

    @Test
    fun aFunctionCannotWaitForTheCore() {
        lateinit var waiting: Core
        val failures = LinkedBlockingQueue<Throwable>()
        val filter = SegmentFilter {
            runCatching { waiting.getStreams() }.exceptionOrNull()?.let(failures::put)
            true
        }
        withCore(testConfig().apply { segmentFilter = filter }) {
            waiting = it
            giveManifests(waiting)
            assertIs<IllegalStateException>(failures.first())
            // The core goes on.
            assertEquals(1, waiting.getStreams().size)
        }
    }

    @Test
    fun aConfigCannotHaveBothFormsOfSwarmId() {
        assertFailsWith<IllegalArgumentException> {
            Core(testConfig().apply { swarmIdFunction = SwarmIdFunction { "x" } }) {
                error("not made")
            }
        }
        // A stream type's own over the one for both: allowed.
        val swarm = SwarmIdFunction { "x" }
        val config =
            testConfig().apply { mainStream = StreamConfig().apply { swarmIdFunction = swarm } }
        Core(config) { throw UnsatisfiedLinkError("no libp2pml") }
            .use { fallback ->
                val given = fallback.getConfig()
                val main = assertNotNull(given.mainStream)
                assertEquals(null to swarm, main.swarmId to main.swarmIdFunction)
                val secondary = assertNotNull(given.secondaryStream)
                assertEquals(SWARM_ID to null, secondary.swarmId to secondary.swarmIdFunction)
            }
    }

    @Test
    fun aDynamicConfigSendsNoStaticFunction() {
        val json =
            DynamicCoreConfig()
                .apply {
                    mainStream =
                        StreamConfig().apply {
                            segmentFilter = SegmentFilter { false }
                            httpRequestSetup = HttpRequestSetup { null }
                        }
                }
                .toJson(FunctionTable())
        val main = assertNotNull(json.optJSONObject("mainStream"))
        assertFalse(main.has("segmentFilter"))
        assertTrue(main.has("httpRequestSetup"))
    }

    @Test
    fun httpRequestSetupGivesTheCoresRequestTheAppsUrlAndHeaders() {
        val contexts = LinkedBlockingQueue<HttpRequestSetupContext>()
        val threads = LinkedBlockingQueue<String>()
        val setup = HttpRequestSetup { context ->
            contexts.put(context)
            threads.put(Thread.currentThread().name)
            when {
                context.segmentUrl.endsWith("/range.ts") ->
                    HttpRequest(url("/signed.ts?t=1"), mapOf("X-Token" to "1"))
                context.segmentUrl.endsWith("/seg1.ts") -> error("no token")
                else -> null
            }
        }
        withCore(testConfig().apply { httpRequestSetup = setup }) { signed ->
            giveManifests(signed)
            assertEquals(RANGE_BYTES, signed.loadSegment(url("/range.ts"), RANGE).data.size)
            assertEquals(
                HttpRequestSetupContext(
                    url("/range.ts"),
                    RANGE,
                    RequestByteRange(RANGE.start, RANGE.end),
                ),
                contexts.first(),
            )
            assertEquals("p2pml-functions", threads.first(), "off the engine thread")
            // null keeps the core's request.
            assertContentEquals(segment, signed.loadSegment(url("/seg0.ts")).data)
            // A throw fails that request.
            assertFailsWith<CoreRequestError> { signed.loadSegment(url("/seg1.ts")) }
            assertSame(setup, assertNotNull(signed.getConfig().mainStream).httpRequestSetup)
        }
    }

    @Test
    fun applyDynamicConfigSetsTheAsyncFunctions() {
        val tokens = LinkedBlockingQueue<String>()
        giveManifests()
        core.applyDynamicConfig(
            DynamicCoreConfig().apply {
                mainStream =
                    StreamConfig().apply {
                        httpRequestSetup = HttpRequestSetup { context ->
                            tokens.put(context.segmentUrl)
                            null
                        }
                        // Static: not in a change.
                        segmentFilter = SegmentFilter { false }
                    }
            }
        )
        assertContentEquals(segment, core.loadSegment(url("/seg0.ts")).data)
        assertEquals(listOf(url("/seg0.ts")), tokens.toList())
        assertTrue(core.hasSegment(url("/seg0.ts")), "the static filter did not come")
    }

    @Test
    fun aValidatorGetsTheSegmentsBytesOnAThreadOfItsOwn() {
        val failures = LinkedBlockingQueue<Throwable>()
        val threads = LinkedBlockingQueue<String>()
        val table = FunctionTable(failed = failures::put)
        val contexts = LinkedBlockingQueue<P2PSegmentContext>()
        val marker =
            table.marker(
                P2PSegmentValidator { context ->
                    contexts.put(context)
                    threads.put(Thread.currentThread().name)
                    context.data.size == 2
                }
            )
        val number = marker.getInt("function")
        val answers = LinkedBlockingQueue<String>()
        val json = """{"url":"https://example.com/seg0.ts","byteRange":{"start":0,"end":1}}"""
        try {
            table.callAsync(number, json, byteArrayOf(1, 2), answers::put)
            assertEquals("""{"value":true}""", answers.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val context = contexts.first()
            assertEquals(ByteRange(0, 1), context.byteRange)
            assertContentEquals(byteArrayOf(1, 2), context.data)
            assertEquals("p2pml-functions", threads.first(), "off the caller's thread")
            // A throw of the answer's send stays on its thread, which would end the app.
            table.callAsync(number, json, null) { error("send failed") }
            val failure = assertNotNull(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("send failed", failure.message)
            // A number that the table does not have still answers: JS waits for it.
            table.callAsync(number + 1, json, null, answers::put)
            assertEquals(
                """{"error":"no such function"}""",
                answers.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            // After the close, no answer: JS takes none.
            table.close()
            table.callAsync(number, json, byteArrayOf(1, 2), answers::put)
            assertEquals(null, answers.poll(NO_EVENT_MS, TimeUnit.MILLISECONDS))
        } finally {
            table.close()
        }
    }

    @Test
    fun asyncFunctionsRunAtTheSameTime() {
        // Each waits for the other: one thread for both would wait for good.
        val both = CountDownLatch(2)
        val table = FunctionTable()
        val number =
            table
                .marker(
                    P2PSegmentValidator {
                        both.countDown()
                        both.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
                )
                .getInt("function")
        val answers = LinkedBlockingQueue<String>()
        try {
            repeat(2) { table.callAsync(number, "{}", null, answers::put) }
            repeat(2) {
                assertEquals(
                    """{"value":true}""",
                    answers.poll(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS),
                )
            }
        } finally {
            table.close()
        }
    }

    @Test
    fun aSegmentComesThroughTheCore() {
        giveManifests()
        assertTrue(core.isSegmentLoadable(url("/seg0.ts")))
        val response = core.loadSegment(url("/seg0.ts"))
        assertContentEquals(segment, response.data)
        assertTrue(response.bandwidth > 0, "bandwidth ${response.bandwidth}")
        assertEquals(StreamType.MAIN, response.streamType)
    }

    @Test
    fun aSegmentOfAnAudioRenditionIsOfASecondaryStream() {
        core.processManifest(url("/master.m3u8"), data = MASTER_WITH_AUDIO)
        core.processManifest(url("/media.m3u8"), data = MEDIA)
        core.processManifest(url("/audio.m3u8"), data = AUDIO)
        val response = core.loadSegment(url("/audio0.ts"))
        assertContentEquals(segment, response.data)
        assertEquals(StreamType.SECONDARY, response.streamType)
    }

    @Test
    fun aManifestGivesItsStreamsAndALiveOneItsPlacement() {
        assertEquals(
            ProcessedManifest(emptyList(), null),
            core.processManifest(url("/master.m3u8"), data = MASTER),
            "a master playlist lists no segments",
        )
        val vod = assertNotNull(core.processManifest(url("/media.m3u8"), data = MEDIA))
        assertEquals(null, vod.liveDelay, "no placement for VOD")
        val stream = vod.streams.single()
        assertEquals(url("/media.m3u8"), stream.key)
        assertEquals(StreamType.MAIN, stream.type)
        assertEquals(false, stream.isLive)
        assertEquals(VOD_SEGMENTS, stream.segmentCount)
        core.destroy()
        core.processManifest(url("/live/master.m3u8"), data = MASTER)
        val live = assertNotNull(core.processManifest(url("/live/media.m3u8"), data = LIVE))
        assertEquals(true, live.streams.single().isLive)
        assertEquals(LIVE_SPAN, live.streams.single().let { it.end - it.start }, "its bounds")
        // The core's rules for 10 segments of 4 s.
        assertEquals(LIVE_PLACEMENT, live.liveDelay)
    }

    // A valid date that the engine's Date cannot read gives the stream bounds as NaN: they cost the
    // placement, not the manifest.
    @Test
    fun aManifestWithBoundsThatAreNotNumbersStillComesBack() {
        core.processManifest(url("/master.m3u8"), data = MASTER)
        val live =
            LIVE.replace("#EXT-X-MEDIA-SEQUENCE:100\n", "#EXT-X-MEDIA-SEQUENCE:100\n$DATE_PLUS_01")
        val manifest = assertNotNull(core.processManifest(url("/media.m3u8"), data = live))
        assertEquals(null, manifest.liveDelay, "no placement")
        val stream = manifest.streams.single()
        assertTrue(
            stream.start.isNaN() && stream.end.isNaN(),
            "bounds ${stream.start} ${stream.end}",
        )
    }

    // A JS error in the event would be an engine line, which stop() rejects.
    @Test
    fun aPlaybackStateReachesTheCore() {
        giveManifests()
        core.updatePlayback(PlaybackState(BUFFER_AHEAD, 1.0))
        assertContentEquals(segment, core.loadSegment(url("/seg0.ts")).data)
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
        core.updatePlayback(PlaybackState(1.0, 1.0))
        assertEquals(null, core.processManifest(url("/media.m3u8"), data = MEDIA))
        assertFalse(core.isSegmentLoadable(url("/seg0.ts")))
        assertIs<CoreRequestError>(loadInBackground("/seg0.ts").second.failure())
        assertEquals(emptyList(), core.getStreams())
        assertEquals(null, core.getStream(url("/media.m3u8")))
        assertFalse(core.hasSegment(url("/seg0.ts")))
        core.applyDynamicConfig(DynamicCoreConfig().apply { isP2PDisabled = true })
        core.setManifestResponseUrl(url("/master.m3u8"))
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
        val fallbackEvents = LinkedBlockingQueue<StreamAddedDetails>()
        fallback.addEventListener(CoreEvent.OnStreamAdded) { fallbackEvents.put(it) }
        giveManifests(fallback)
        fallback.updatePlayback(PlaybackState(1.0, 1.0))
        // An event would come later, on the event thread.
        assertEquals(null, fallbackEvents.poll(NO_EVENT_MS, TimeUnit.MILLISECONDS), "no core")
        val listener = CoreEventListener<StreamAddedDetails> {}
        fallback.addEventListener(CoreEvent.OnStreamAdded, listener)
        fallback.removeEventListener(CoreEvent.OnStreamAdded, listener)
        assertEquals(null, fallback.processManifest(url("/media.m3u8"), data = MEDIA))
        assertFalse(fallback.isSegmentLoadable(url("/seg0.ts")))
        assertFailsWith<CoreRequestError> { fallback.loadSegment(url("/seg0.ts")) }
        assertEquals(emptyList(), fallback.getStreams())
        assertEquals(null, fallback.getStream(url("/media.m3u8")))
        assertFalse(fallback.hasSegment(url("/seg0.ts")))
        fallback.applyDynamicConfig(DynamicCoreConfig().apply { isP2PDisabled = true })
        fallback.setManifestResponseUrl(url("/master.m3u8"))
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
        const val FORBIDDEN = 403
        const val DEFAULT_HTTP_TRIES = 3
        const val MAIN_HTTP_TRIES = 5
        const val SWARM_ID = "a\"b"
        const val MEMORY_MIB = 64.0
        const val BUFFER_AHEAD = 30.0
        // The range of range.ts in MEDIA, and another one.
        val RANGE = ByteRange(0, 999)
        val OTHER_RANGE = ByteRange(0, 499)
        const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n"
        const val BANDWIDTH = 1_280_000.0
        const val SEGMENT_SECONDS = 4.0
        // Where range.ts, the last segment of MEDIA, starts.
        const val LAST_START = 12.0
        const val VOD_SEGMENTS = 4
        val LIVE_PLACEMENT = LiveDelay(36.0, 4.0, 32.0, 44.0)
        const val NO_EVENT_MS = 200L
        const val LIVE_SPAN = 40.0
        const val DATE_PLUS_01 = "#EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:00.000+01\n"
        val LIVE =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:100\n" +
                (0 until 10).joinToString("") { "#EXTINF:4.0,\nseg${100 + it}.ts\n" }
        const val MASTER_WITH_AUDIO =
            "#EXTM3U\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",DEFAULT=YES,URI=\"audio.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"a\"\nmedia.m3u8\n"
        const val AUDIO =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4.0,\naudio0.ts\n#EXT-X-ENDLIST\n"
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
                "#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXTINF:4.0,\nseg2.ts\n" +
                "#EXTINF:4.0,\n#EXT-X-BYTERANGE:1000@0\nrange.ts\n#EXT-X-ENDLIST\n"
        // MEDIA with an ad break that starts at seg1.ts.
        val MEDIA_WITH_CUE =
            MEDIA.replace(
                "#EXTINF:4.0,\nseg1.ts",
                "#EXT-X-DISCONTINUITY\n#EXT-X-CUE-OUT:30\n#EXTINF:4.0,\nseg1.ts",
            )

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
