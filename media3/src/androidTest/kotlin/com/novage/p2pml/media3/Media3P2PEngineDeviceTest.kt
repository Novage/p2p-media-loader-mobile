package com.novage.p2pml.media3

import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.novage.p2pml.core.Core
import com.novage.p2pml.core.CoreEvent
import com.novage.p2pml.core.LiveDelay
import com.novage.p2pml.core.PlaybackState
import com.novage.p2pml.core.ProcessedManifest
import com.novage.p2pml.core.ProcessedStream
import com.novage.p2pml.core.StreamType
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

// The engine's media sources, on a thread with a looper, as Media3's playback thread.
@OptIn(UnstableApi::class)
class Media3P2PEngineDeviceTest {
    private val bMasters = AtomicInteger()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/a/master.m3u8" -> MockResponse.Builder().body(MASTER).build()
                            // The second time, longer than any test waits: the core has only what
                            // it kept.
                            "/b/master.m3u8" ->
                                if (bMasters.incrementAndGet() == 1) {
                                    MockResponse.Builder().body(MASTER).build()
                                } else {
                                    MockResponse.Builder()
                                        .headersDelay(SLOW_SECONDS, TimeUnit.SECONDS)
                                        .build()
                                }
                            "/a/media.m3u8",
                            "/b/media.m3u8" -> MockResponse.Builder().body(MEDIA).build()
                            "/c/master.m3u8" -> MockResponse.Builder().body(MASTER).build()
                            "/c/media.m3u8" -> MockResponse.Builder().body(LIVE_MEDIA).build()
                            else -> MockResponse.Builder().code(NOT_FOUND).build()
                        }
                }
            start()
        }
    private val core = Core(testConfig())
    // Each playback state that the engine gives the core.
    private val reports = ConcurrentLinkedQueue<PlaybackState>()
    private val engine =
        Media3P2PEngine(core) { state ->
            reports += state
            core.updatePlayback(state)
        }
    // The core's onSegmentLoaded events, through the engine.
    private val loadedEvents =
        AtomicInteger().also { count ->
            engine.addEventListener(CoreEvent.OnSegmentLoaded) { count.incrementAndGet() }
        }
    private val looper = HandlerThread("media3-test").apply { start() }
    private val handler = Handler(looper.looper)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @AfterTest
    fun stop() {
        looper.quitSafely()
        engine.close()
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    // Runs [block] on the looper thread and gives its result.
    private fun <T> onLooper(block: () -> T): T {
        val result = CompletableFuture<T>()
        handler.post { runCatching(block).fold(result::complete, result::completeExceptionally) }
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun awaitLoadable(url: String) =
        awaitUntil("$url is loadable") { core.isSegmentLoadable(url) }

    // Every setter that Media3 has, the deprecated one too.
    @Suppress("DEPRECATION")
    @Test
    fun eachSetterGivesTheEngineFactoryBack() {
        val factory = engine.mediaSourceFactory(DefaultHttpDataSource.Factory())
        val results =
            listOf(
                factory.setDrmSessionManagerProvider(DefaultDrmSessionManagerProvider()),
                factory.setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy()),
                factory.setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT),
                factory.setSubtitleParserFactory(DefaultSubtitleParserFactory()),
                factory.experimentalParseSubtitlesDuringExtraction(true),
                factory.experimentalSetCodecsToParseWithinGopSampleDependencies(0),
                factory.setDownloadExecutor { error("not used") },
            )
        for (result in results) assertSame(factory, result)
        // A setter that a new Media3 adds needs an override too.
        fun Method.signature() = name + parameterTypes.toList()
        val overridden = factory.javaClass.declaredMethods.map { it.signature() }.toSet()
        val setters =
            MediaSource.Factory::class.java.methods.filter {
                it.returnType == MediaSource.Factory::class.java
            }
        assertEquals(emptyList(), setters.map { it.signature() }.filterNot { it in overridden })
    }

    @Test
    fun eachSourceReadiesTheCoreForItself() {
        // Through a chained setter, as an app may give it to the player.
        val factory =
            engine
                .mediaSourceFactory(DefaultHttpDataSource.Factory())
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy())
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        val meter = DefaultBandwidthMeter.Builder(context).build()
        val sources =
            listOf("/a/master.m3u8", "/b/master.m3u8").map { path ->
                factory.createMediaSource(MediaItem.fromUri(url(path)))
            }
        val prepared = sources.toMutableList()
        try {
            onLooper { sources[0].prepareSource(caller, PlayerId.UNSET, meter) }
            awaitLoadable(url("/a/seg0.ts"))
            onLooper { sources[1].prepareSource(caller, PlayerId.UNSET, meter) }
            awaitLoadable(url("/b/seg0.ts"))
            assertFalse(core.isSegmentLoadable(url("/a/seg0.ts")), "the first source is gone")
            // As after an error: Media3 releases the sources, and prepares them again.
            onLooper { sources.forEach { it.releaseSource(caller) } }
            prepared.clear()
            assertTrue(core.isSegmentLoadable(url("/b/seg0.ts")), "a release keeps the core")
            onLooper { sources[1].prepareSource(caller, PlayerId.UNSET, meter) }
            prepared += sources[1]
            assertTrue(core.isSegmentLoadable(url("/b/seg0.ts")), "a new prepare keeps the core")
        } finally {
            // Its failure must not hide the test's.
            runCatching { onLooper { prepared.forEach { it.releaseSource(caller) } } }
        }
    }

    @Test
    fun aReleaseOfTheSourceThatTheCoreServesDropsItsPlacement() {
        val factory = engine.mediaSourceFactory(DefaultHttpDataSource.Factory())
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        val meter = DefaultBandwidthMeter.Builder(context).build()
        // A VOD source, then a live one, which the core places.
        val (vod, live) =
            listOf("/a/master.m3u8", "/c/master.m3u8").map { path ->
                factory.createMediaSource(MediaItem.fromUri(url(path)))
            }
        val prepared = mutableListOf(vod, live)
        try {
            onLooper { vod.prepareSource(caller, PlayerId.UNSET, meter) }
            awaitLoadable(url("/a/seg0.ts"))
            onLooper { live.prepareSource(caller, PlayerId.UNSET, meter) }
            awaitUntil("the live source placed") { engine.placement != null }
            onLooper { vod.releaseSource(caller) }
            prepared -= vod
            assertNotNull(engine.placement, "a release of an older source keeps it")
            onLooper { live.releaseSource(caller) }
            prepared -= live
            assertNull(engine.placement, "a release of the source that the core serves")
            // As after an error: the same source, prepared again, keeps the core, and its
            // playlist places it again.
            onLooper { live.prepareSource(caller, PlayerId.UNSET, meter) }
            prepared += live
            awaitUntil("placed again") { engine.placement != null }
        } finally {
            // Its failure must not hide the test's.
            runCatching { onLooper { prepared.forEach { it.releaseSource(caller) } } }
        }
    }

    // Needs the internet: the BBB live stream, fMP4 with an init segment for each rendition.
    @Test
    fun exoPlayerPlaysALiveStreamThroughTheCore() {
        val upstreamPaths = ConcurrentLinkedQueue<String>()
        val upstream =
            ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { spec ->
                upstreamPaths += spec.uri.path.orEmpty()
                spec
            }
        val segments = ConcurrentLinkedQueue<String>()
        val ready = CompletableFuture<Unit>()
        val error = AtomicReference<PlaybackException>()
        val recoveries = AtomicInteger()
        val player = onLooper {
            engine
                .buildPlayer(context, ExoPlayer.Builder(context).setLooper(looper.looper), upstream)
                .apply {
                    addListener(readiness(ready, error, recoveries))
                    addAnalyticsListener(
                        object : AnalyticsListener {
                            override fun onLoadCompleted(
                                eventTime: AnalyticsListener.EventTime,
                                loadEventInfo: LoadEventInfo,
                                mediaLoadData: MediaLoadData,
                            ) {
                                val uri = loadEventInfo.uri
                                if (uri.path.orEmpty().endsWith(".m4s")) segments += "$uri"
                            }
                        }
                    )
                    setMediaItem(MediaItem.fromUri(STREAM_URL))
                    prepare()
                    play()
                }
        }
        try {
            ready.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            checkThePlacement(player)
            val start = onLooper { player.periodPosition() }
            Thread.sleep(TimeUnit.SECONDS.toMillis(PLAY_SECONDS))
            val played = onLooper { player.periodPosition() } - start
            assertNull(error.get(), "no player error but BEHIND_LIVE_WINDOW")
            // Each recovery jumps forward, which would hide repeated stalls in `played`.
            assertTrue(recoveries.get() <= 1, "${recoveries.get()} BEHIND_LIVE_WINDOW recoveries")
            assertTrue(played >= TimeUnit.SECONDS.toMillis(PLAY_SECONDS) / 2, "played $played ms")
            assertLoaded(segments)
            assertEquals(
                emptyList(),
                upstreamPaths.filter { it.endsWith(".m4s") },
                "no segment from upstream",
            )
            assertTrue(
                reports.any { it.rate == 1.0 && it.bufferAhead > 1.0 },
                "a report of the playback: ${reports.toList().takeLast(REPORTS_SHOWN)}",
            )
            // A pause is a rate of 0.
            // Media3 gives the events of a call a moment after it.
            onLooper { player.pause() }
            awaitUntil("a report of the pause") { reports.last().rate == 0.0 }
            stopAndPrepareInACallback(player, segments.last())
            // A stop takes the core out of its swarm; a prepare readies it again.
            val segment = segments.last()
            assertTrue(core.isSegmentLoadable(segment), "the core knows $segment")
            onLooper { player.stop() }
            assertFalse(core.isSegmentLoadable(segment), "the core left after the stop")
            onLooper { player.prepare() }
            awaitLoadable(segment)
            // The stop took the placement (destroy); the prepared source's playlist gives it again.
            awaitUntil("placed again") { engine.placement != null }
            // So does a player with no item.
            onLooper { player.clearMediaItems() }
            assertFalse(core.isSegmentLoadable(segment), "the core left with the items")
        } finally {
            onLooper { player.release() }
        }
    }

    @Test
    fun aLiveMainPlaylistPlacesThePlayerAndAnEndedOneDoesNot() {
        fun manifest(type: StreamType, isLive: Boolean, liveDelay: LiveDelay?) =
            ProcessedManifest(
                listOf(ProcessedStream(url("/live.m3u8"), type, isLive, 0.0, 40.0, 10, null)),
                liveDelay,
            )
        engine.onManifest(manifest(StreamType.MAIN, true, PLACEMENT))
        assertEquals(PLACEMENT, engine.placement)
        engine.onManifest(ProcessedManifest(emptyList(), null))
        engine.onManifest(manifest(StreamType.SECONDARY, true, AUDIO_PLACEMENT))
        assertEquals(PLACEMENT, engine.placement, "a master or an audio playlist keeps it")
        engine.onManifest(manifest(StreamType.MAIN, true, null))
        assertEquals(PLACEMENT, engine.placement, "a live main playlist with no segments keeps it")
        engine.onManifest(manifest(StreamType.MAIN, false, null))
        assertNull(engine.placement, "an ended main playlist")
    }

    // A live player whose next segment left the window starts again; another error stays.
    @Test
    fun theWatchStartsAPlayerBehindTheLiveWindowAgain() {
        val player = onLooper {
            // Not prepared: the checks read the player's state before it loads anything.
            ExoPlayer.Builder(context).setLooper(looper.looper).build().apply {
                setMediaItem(MediaItem.fromUri(url("/a/media.m3u8")))
                seekTo(AWAY_MS)
            }
        }
        try {
            val watch = PlayerWatch(player, {}, {}) { null }
            fun failWith(code: Int) = onLooper {
                watch.recoverFrom(PlaybackException("test", null, code))
                player.playbackState to player.currentPosition
            }
            assertEquals(
                Player.STATE_IDLE to AWAY_MS,
                failWith(PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
            )
            assertEquals(
                Player.STATE_BUFFERING to 0L,
                failWith(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW),
                "prepared again, at the default position",
            )
        } finally {
            onLooper { player.release() }
        }
    }

    @Test
    fun theEngineBuildsOnePlayer() {
        val (player, reported) =
            onLooper {
                engine.buildPlayer(context, ExoPlayer.Builder(context).setLooper(looper.looper)) to
                    reports.size
            }
        try {
            // The watch starts before the app's first call to the player.
            assertTrue(reported > 0, "a report before buildPlayer returns")
            // It throws before it builds anything, on any thread.
            assertFailsWith<IllegalStateException> {
                engine.buildPlayer(context, ExoPlayer.Builder(context).setLooper(looper.looper))
            }
        } finally {
            onLooper { player.release() }
        }
    }

    // Completes [ready] at the first READY, and records a player error in [error] and [ready]; a
    // BEHIND_LIVE_WINDOW, from a stall on a slow network, only counts: the engine recovers it.
    private fun readiness(
        ready: CompletableFuture<Unit>,
        error: AtomicReference<PlaybackException>,
        recoveries: AtomicInteger,
    ) =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) ready.complete(Unit)
            }

            override fun onPlayerError(playerError: PlaybackException) {
                if (playerError.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    recoveries.incrementAndGet()
                } else {
                    error.set(playerError)
                    ready.completeExceptionally(playerError)
                }
            }
        }

    // The player loaded segments, and the core told the engine's listener.
    private fun assertLoaded(segments: Collection<String>) {
        assertTrue(segments.size >= MIN_SEGMENTS, "${segments.size} segments loaded")
        assertTrue(loadedEvents.get() > 0, "the core's onSegmentLoaded through the engine")
    }

    // The core placed the player deep in the live window, at the start of a segment, and not where
    // Media3 places it (three target durations behind the edge).
    private fun checkThePlacement(player: ExoPlayer) {
        val placement = assertNotNull(engine.placement, "the core placed the player")
        val (target, behind) =
            onLooper {
                val window =
                    player.currentTimeline.getWindow(
                        player.currentMediaItemIndex,
                        Timeline.Window(),
                    )
                // With a program date time, the target holds the window's edge offset.
                val edge =
                    if (window.windowStartTimeMs == C.TIME_UNSET) 0L
                    else window.currentUnixTimeMs - window.windowStartTimeMs - window.durationMs
                window.liveConfiguration?.targetOffsetMs?.minus(edge) to
                    window.durationMs - player.currentPosition
            }
        val delayMs = (placement.delay * C.MILLIS_PER_SECOND).toLong()
        val segmentMs = (placement.segment * C.MILLIS_PER_SECOND).toLong()
        // The target holds the edge offset of when the timeline was built, at most a playlist
        // refresh (a segment) ago; the edge offset read here is of now.
        val clockMs = 100L
        assertTrue(
            target != null && target in delayMs - segmentMs - clockMs..delayMs + clockMs,
            "target offset less the edge offset: $target ms, delay $delayMs ms",
        )
        assertTrue(
            behind in delayMs - segmentMs..delayMs + 2 * segmentMs,
            "$behind ms behind the edge, delay $delayMs ms",
        )
    }

    // A stop and a prepare inside another callback: the stop's event comes after the prepare,
    // and must not take the core from the prepared source, which knows [segment].
    private fun stopAndPrepareInACallback(player: ExoPlayer, segment: String) {
        onLooper {
            player.addListener(
                object : Player.Listener {
                    override fun onPlaybackParametersChanged(
                        playbackParameters: PlaybackParameters
                    ) {
                        player.removeListener(this)
                        player.stop()
                        player.prepare()
                    }
                }
            )
            player.setPlaybackSpeed(SLOWER)
        }
        // The events that the call left.
        onLooper {}
        assertTrue(core.isSegmentLoadable(segment), "the prepared source keeps the core")
    }

    // The position in the period: in a live stream, the window moves on with each playlist, and
    // the position in it with the window.
    private fun Player.periodPosition() =
        currentPosition -
            currentTimeline.getPeriod(currentPeriodIndex, Timeline.Period()).positionInWindowMs

    private companion object {
        const val SLOW_SECONDS = 60L
        const val PLAY_SECONDS = 20L
        const val MIN_SEGMENTS = 3
        const val REPORTS_SHOWN = 5
        const val SLOWER = 0.9f
        const val AWAY_MS = 5_000L
        val AUDIO_PLACEMENT = LiveDelay(10.0, 2.0, 8.0, 14.0)
        const val STREAM_URL = "https://live.novage.workers.dev/bbb/live.m3u8"
        // 10 segments of 4 s, with no end: the core places it.
        val LIVE_MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                (0 until 10).joinToString("") { "#EXTINF:4.0,\nseg$it.ts\n" }
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4.0,\nseg0.ts\n#EXT-X-ENDLIST\n"
    }
}
