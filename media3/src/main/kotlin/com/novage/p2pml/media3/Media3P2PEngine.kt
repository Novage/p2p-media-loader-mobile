package com.novage.p2pml.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Supplier
import com.novage.p2pml.core.Core
import com.novage.p2pml.core.CoreConfig
import com.novage.p2pml.core.CoreEvent
import com.novage.p2pml.core.CoreEventListener
import com.novage.p2pml.core.DynamicCoreConfig
import com.novage.p2pml.core.LiveDelay
import com.novage.p2pml.core.PlaybackState
import com.novage.p2pml.core.ProcessedManifest
import com.novage.p2pml.core.ProcessedStream
import com.novage.p2pml.core.StreamType
import java.io.InterruptedIOException

/** The settings of a [Media3P2PEngine], as the web library's `HlsJsP2PEngineConfig`. */
public class Media3P2PEngineConfig
@JvmOverloads
constructor(
    /** The core's settings. */
    public var core: CoreConfig = CoreConfig()
)

/**
 * The changes of a [Media3P2PEngine]'s settings while it plays, as the web library's
 * `DynamicHlsJsP2PEngineConfig`: for [Media3P2PEngine.applyDynamicConfig].
 */
public class DynamicMedia3P2PEngineConfig
@JvmOverloads
constructor(
    /** The changes of the core's settings; null changes nothing. */
    public var core: DynamicCoreConfig? = null
)

/**
 * Plays HLS in Media3 through the P2P Media Loader core, as the web library's `HlsJsP2PEngine`
 * plays it in HLS.js (specs/api.md, "Media3"). [buildPlayer] makes the player: the core gets every
 * playlist, loads the segments it knows, and gets the player's playback state; everything else goes
 * to the given data sources.
 *
 * The engine serves one player and plays one source at a time: each source readies the core for it
 * when the player prepares it. [close] ends the core; close the engine after the player.
 */
public class Media3P2PEngine
internal constructor(
    private val core: Core,
    // Where the playback state goes: the core, or a test.
    private val onPlayback: (PlaybackState) -> Unit = core::updatePlayback,
) : AutoCloseable {
    @JvmOverloads
    public constructor(
        config: Media3P2PEngineConfig = Media3P2PEngineConfig()
    ) : this(Core(config.core))

    // The source that the core serves: the one that the player prepared last. Media3 prepares
    // sources on its playback thread; a stop forgets it on the player's thread.
    @Volatile private var coreSource: MediaSource? = null
    @Volatile private var watch: PlayerWatch? = null
    // Where the core places a live player of the current source (its last live main playlist), or
    // null. Set on a loading thread, read on the playback and the application threads.
    @Volatile
    internal var placement: LiveDelay? = null
        private set

    /**
     * The settings that the engine uses, as `HlsJsP2PEngine.getConfig()`: the core's, with its
     * defaults.
     *
     * @throws InterruptedIOException when the waiting thread is interrupted.
     */
    @Throws(InterruptedIOException::class)
    public fun getConfig(): Media3P2PEngineConfig = Media3P2PEngineConfig(core.getConfig())

    /**
     * Builds the player that plays through the core, as the web library's `HlsJsP2PEngine` with
     * HLS.js. On [builder], it sets the media source factory, of HLS sources placed in a live
     * window by the core, the bandwidth meter, which takes the core's bandwidth for the core's
     * segments, and the load control, which holds a live player's forward buffer to the core's
     * rule; settings of these three on [builder] do not stay. Then it watches the player for the
     * core: its playback state, a stop, which takes the core out of its swarm, and a live player
     * that falls too far behind, which it moves back to its place. A live player that fails with
     * `ERROR_CODE_BEHIND_LIVE_WINDOW` starts again at its default position, its place when the core
     * placed it (`seekToDefaultPosition` and `prepare`), as Media3 recommends: the app's listeners
     * still get the error, and the app needs no recovery of its own. [upstream] loads what the core
     * does not: the playlists, the segments that the core does not know, keys and every other
     * request.
     *
     * Call it on the thread that uses the player, once: the engine serves one player.
     *
     * @throws IllegalStateException when the engine has a player already.
     */
    @OptIn(UnstableApi::class)
    @JvmOverloads
    public fun buildPlayer(
        context: Context,
        builder: ExoPlayer.Builder = ExoPlayer.Builder(context),
        upstream: DataSource.Factory = DefaultDataSource.Factory(context),
    ): ExoPlayer {
        check(watch == null) { "The engine has a player already" }
        val meter = CoreBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context))
        val player =
            builder
                .setMediaSourceFactory(mediaSourceFactory(upstream))
                .setBandwidthMeter(meter)
                .setLoadControl(CoreLoadControl(DefaultLoadControl()) { placement })
                .build()
        val watch = PlayerWatch(player, onPlayback, ::destroy) { placement }
        // Set after the start, which throws on a thread that is not the player's.
        watch.start()
        this.watch = watch
        return player
    }

    /** The media sources that play through the core. */
    @OptIn(UnstableApi::class)
    internal fun mediaSourceFactory(upstream: DataSource.Factory): MediaSource.Factory =
        CoreSourceFactory(
            HlsMediaSource.Factory(P2pHlsDataSourceFactory(core, upstream, ::onManifest))
        )

    // A live main playlist places the player, as the web adapters place theirs from the main
    // stream; a main playlist that is not live, such as one that ended, does not. One that the core
    // gives no placement for keeps the last: one with no segments, or one whose times the core
    // cannot read (KNOWN-ISSUES.md). An audio playlist is of a secondary stream, and a master
    // playlist lists none.
    internal fun onManifest(manifest: ProcessedManifest) {
        val main = manifest.streams.filter { it.type == StreamType.MAIN }
        if (main.isEmpty()) return
        if (main.none(ProcessedStream::isLive)) placement = null
        else manifest.liveDelay?.let { placement = it }
    }

    /**
     * Changes the settings that the core can change while it plays, as
     * `HlsJsP2PEngine.applyDynamicConfig`: see [Core.applyDynamicConfig]. For example, P2P off on a
     * metered network.
     *
     * @throws org.json.JSONException when a number of [config] is not finite.
     */
    public fun applyDynamicConfig(config: DynamicMedia3P2PEngineConfig) {
        config.core?.let(core::applyDynamicConfig)
    }

    /** The core's events, as `HlsJsP2PEngine.addEventListener`: see [Core.addEventListener]. */
    public fun <T> addEventListener(event: CoreEvent<T>, listener: CoreEventListener<T>) {
        core.addEventListener(event, listener)
    }

    /** As `HlsJsP2PEngine.removeEventListener`: see [Core.removeEventListener]. */
    public fun <T> removeEventListener(event: CoreEvent<T>, listener: CoreEventListener<T>) {
        core.removeEventListener(event, listener)
    }

    /**
     * Readies the core for the next source, as `HlsJsP2PEngine.destroy()`: its streams, segments
     * and peers go. The engine calls it itself when the app stops the player; the next source that
     * the player prepares readies the core again.
     */
    public fun destroy() {
        coreSource = null
        placement = null
        core.destroy()
    }

    /** Ends the core and the watch of the player: the segments load through upstream after it. */
    override fun close() {
        watch?.stop()
        core.close()
    }

    /**
     * Makes the engine's sources. Its setters give this factory back, not the one that it wraps, so
     * that a chained setter keeps the sources of the engine.
     */
    @OptIn(UnstableApi::class)
    private inner class CoreSourceFactory(factory: MediaSource.Factory) :
        ForwardingMediaSourceFactory(factory) {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            CoreSource(super.createMediaSource(mediaItem))

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply {
            super.setDrmSessionManagerProvider(provider)
        }

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply {
            super.setLoadErrorHandlingPolicy(policy)
        }

        override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory) = apply {
            super.setCmcdConfigurationFactory(factory)
        }

        override fun setSubtitleParserFactory(factory: SubtitleParser.Factory) = apply {
            super.setSubtitleParserFactory(factory)
        }

        override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean) = apply {
            super.experimentalParseSubtitlesDuringExtraction(parse)
        }

        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int) = apply {
            super.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
        }

        override fun setDownloadExecutor(executor: Supplier<ReleasableExecutor>) = apply {
            super.setDownloadExecutor(executor)
        }
    }

    /**
     * A source of the engine: it readies the core for itself when the player prepares it, as
     * HLS.js's engine does when a source loads. Media3 prepares a source on its playback thread
     * before the source loads anything, so the core is ready before the source's first playlist.
     *
     * After an error, Media3 releases the source and prepares it again: the source then keeps the
     * core, as HLS.js's engine does when it recovers. The core recovers on its own, and falls back
     * to HTTP where it must.
     */
    @OptIn(UnstableApi::class)
    private inner class CoreSource(source: MediaSource) : WrappingMediaSource(source) {
        override fun prepareSourceInternal() {
            if (coreSource !== this) {
                coreSource = this
                placement = null
                core.destroy()
            }
            super.prepareSourceInternal()
        }

        // The source that the core serves takes its placement with it when Media3 releases it, as
        // when the app plays a source of its own. Media3 releases this source before or after it
        // prepares the next source of the engine; either way the next one starts with no
        // placement. After an error or a stop, Media3 prepares this source again, and its first
        // live main playlist with segments places it again before Media3 builds its timeline; a
        // first playlist with no segments leaves Media3's placement until one has segments.
        override fun releaseSourceInternal() {
            if (coreSource === this) placement = null
            super.releaseSourceInternal()
        }

        // The core places each timeline of a live main playlist.
        override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
            refreshSourceInfo(placement?.let { PlacedTimeline(newTimeline, it) } ?: newTimeline)
        }
    }
}
