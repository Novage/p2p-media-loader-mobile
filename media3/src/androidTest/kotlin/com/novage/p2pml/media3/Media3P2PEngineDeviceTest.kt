package com.novage.p2pml.media3

import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
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
import com.novage.p2pml.core.PlaybackState
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
        val player = onLooper {
            engine
                .buildPlayer(context, ExoPlayer.Builder(context).setLooper(looper.looper), upstream)
                .apply {
                    addListener(
                        object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) ready.complete(Unit)
                            }

                            override fun onPlayerError(playerError: PlaybackException) {
                                error.set(playerError)
                                ready.completeExceptionally(playerError)
                            }
                        }
                    )
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
            val start = onLooper { player.periodPosition() }
            Thread.sleep(TimeUnit.SECONDS.toMillis(PLAY_SECONDS))
            val played = onLooper { player.periodPosition() } - start
            assertNull(error.get(), "no player error")
            assertTrue(played >= TimeUnit.SECONDS.toMillis(PLAY_SECONDS) / 2, "played $played ms")
            assertTrue(segments.size >= MIN_SEGMENTS, "${segments.size} segments loaded")
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
            // So does a player with no item.
            onLooper { player.clearMediaItems() }
            assertFalse(core.isSegmentLoadable(segment), "the core left with the items")
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
        const val STREAM_URL = "https://live.novage.workers.dev/bbb/live.m3u8"
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4.0,\nseg0.ts\n#EXT-X-ENDLIST\n"
    }
}
