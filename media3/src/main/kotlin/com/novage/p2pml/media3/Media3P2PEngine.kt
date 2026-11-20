package com.novage.p2pml.media3

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Supplier
import com.novage.p2pml.core.Core
import com.novage.p2pml.core.CoreConfig
import java.io.InterruptedIOException

/** The settings of a [Media3P2PEngine], as the web library's `HlsJsP2PEngineConfig`. */
public class Media3P2PEngineConfig
@JvmOverloads
constructor(
    /** The core's settings. */
    public var core: CoreConfig = CoreConfig()
)

/**
 * Plays HLS in Media3 through the P2P Media Loader core, as the web library's `HlsJsP2PEngine`
 * plays it in HLS.js (specs/api.md, "Media3"). Give the player [mediaSourceFactory]: the core gets
 * every playlist and loads the segments it knows; everything else goes to the given data sources.
 *
 * The engine serves one player and plays one source at a time: each source that the factory makes
 * readies the core for it when the player prepares it. [close] ends the core; close the engine
 * after the player.
 */
public class Media3P2PEngine internal constructor(private val core: Core) : AutoCloseable {
    @JvmOverloads
    public constructor(
        config: Media3P2PEngineConfig = Media3P2PEngineConfig()
    ) : this(Core(config.core))

    // The source that the core serves: the one that the player prepared last. Media3 prepares
    // sources on its playback thread.
    private var coreSource: MediaSource? = null

    /**
     * The settings that the engine uses, as `HlsJsP2PEngine.getConfig()`: the core's, with its
     * defaults.
     *
     * @throws InterruptedIOException when the waiting thread is interrupted.
     */
    @Throws(InterruptedIOException::class)
    public fun getConfig(): Media3P2PEngineConfig = Media3P2PEngineConfig(core.getConfig())

    /**
     * A factory of HLS media sources that play through the core, for
     * `ExoPlayer.Builder.setMediaSourceFactory`. [upstream] loads what the core does not: the
     * playlists, the segments that the core does not know, keys and every other request.
     */
    @OptIn(UnstableApi::class)
    public fun mediaSourceFactory(upstream: DataSource.Factory): MediaSource.Factory =
        CoreSourceFactory(HlsMediaSource.Factory(P2pHlsDataSourceFactory(core, upstream)))

    /**
     * Readies the core for the next source, as `HlsJsP2PEngine.destroy()`: its streams, segments
     * and peers go. The factory's sources call it themselves.
     */
    public fun destroy() {
        core.destroy()
    }

    /** Ends the core: the segments load through upstream after it. */
    override fun close() {
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
                core.destroy()
            }
            super.prepareSourceInternal()
        }
    }
}
