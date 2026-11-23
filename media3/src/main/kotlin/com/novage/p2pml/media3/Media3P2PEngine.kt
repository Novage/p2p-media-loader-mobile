package com.novage.p2pml.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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
 * playlist of the source that it serves, loads the segments it knows, and gets the player's
 * playback state; everything else goes to the given data sources.
 *
 * The engine serves one player. The core serves the source of the player's current item: a source
 * that Media3 prepares ahead, as the next item of a playlist or a preload, loads over HTTP until
 * its item plays. [close] ends the core; close the engine after the player.
 */
public class Media3P2PEngine
internal constructor(
    private val core: Core,
    // Where the playback state goes: the core, or a test.
    private val onPlayback: (PlaybackState) -> Unit = core::updatePlayback,
) : AutoCloseable {
    /**
     * @throws IllegalArgumentException when a config of [Media3P2PEngineConfig.core] has both
     *   `swarmId` and `swarmIdFunction`.
     */
    @JvmOverloads
    public constructor(
        config: Media3P2PEngineConfig = Media3P2PEngineConfig()
    ) : this(Core(config.core))

    // The engine's sources that Media3 has prepared, in the order of their prepare. Media3 prepares
    // and releases them on its playback thread; the player's thread reads them.
    private val prepared = CopyOnWriteArrayList<CoreSource>()
    // The source that the core serves: the current item's. Set on the player's thread, read on the
    // loading threads.
    @Volatile private var served: CoreSource? = null
    // Gives a source's playlists to the core when it gets the core, off the player's thread.
    private val handOvers = Executors.newSingleThreadExecutor { task ->
        Thread(task, "p2pml-handover").apply { isDaemon = true }
    }
    @Volatile private var watch: PlayerWatch? = null
    // The lock of the served source: a private one, which no app code holds.
    private val lock = Any()
    // Where the core places a live player of the served source, or null: that source's own, so
    // that no thread writes another source's.
    internal val placement: LiveDelay?
        get() = served?.link?.placement

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
        val watch = PlayerWatch(player, onPlayback, ::serveItem) { placement }
        // Set after the start, which throws on a thread that is not the player's.
        watch.start()
        this.watch = watch
        return player
    }

    /** The media sources that play through the core. */
    @OptIn(UnstableApi::class)
    internal fun mediaSourceFactory(upstream: DataSource.Factory): MediaSource.Factory =
        CoreSourceFactory(upstream)

    /**
     * Gives the core to the source of [item], the player's current item, or to none (null): of the
     * sources that Media3 prepared, the last one of the item, or the served one, which Media3
     * releases and prepares again in a recovery. An item that is not the engine's has none. A
     * source is the item's when it loads the item's URL, as when the app replaces an item in place
     * with other metadata or another tag. Media3 also makes a new source for another DRM or other
     * stream keys: that one gets the core when Media3 prepares it.
     */
    @OptIn(UnstableApi::class)
    internal fun serveItem(item: MediaItem?) {
        val source =
            item?.localConfiguration?.uri?.let { url ->
                prepared.lastOrNull { it.mediaItem.localConfiguration?.uri == url }
                    ?: served?.takeIf { it.mediaItem.localConfiguration?.uri == url }
            }
        serve(source)
    }

    /**
     * Gives the core to [source], or to none: the core leaves the last source's swarm at once, and
     * gets [source]'s playlists on the hand-over thread. No wait: the player's thread calls it.
     */
    private fun serve(source: CoreSource?) {
        synchronized(lock) {
            if (source === served) return
            destroy()
            if (source == null) return
            served = source
            try {
                handOvers.execute(source.link::handOver)
            } catch (_: RejectedExecutionException) {
                // The engine is closed, and its core takes nothing: a source can change after it.
            }
        }
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
     * and peers go. The engine does it itself when the player's current item changes, and when the
     * app stops the player; the current item's source gets the core again at the player's next
     * change.
     */
    public fun destroy() {
        synchronized(lock) {
            served = null
            core.destroy()
        }
    }

    /** Ends the core and the watch of the player: the segments load through upstream after it. */
    override fun close() {
        watch?.stop()
        handOvers.shutdownNow()
        core.close()
    }

    /**
     * Makes the engine's sources, each with data sources of its own, which know whether their
     * source has the core. It keeps the values of its setters for each source's HLS factory; its
     * setters give this factory back, so that a chained setter keeps the sources of the engine.
     */
    @OptIn(UnstableApi::class, ExperimentalApi::class)
    private inner class CoreSourceFactory(private val upstream: DataSource.Factory) :
        MediaSource.Factory {
        // The last value of each setter, by its name.
        private val settings = LinkedHashMap<String, (MediaSource.Factory) -> Unit>()
        // One for all the sources, as one HLS factory has: items of the same DRM configuration
        // share their session manager. A provider that the app sets replaces it.
        private val drm = DefaultDrmSessionManagerProvider()

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val link = Link()
            val factory =
                HlsMediaSource.Factory(P2pHlsDataSourceFactory(core, upstream, link))
                    .setDrmSessionManagerProvider(drm)
            for (setting in settings.values) setting(factory)
            return CoreSource(link, factory.createMediaSource(mediaItem))
        }

        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_HLS)

        private fun keep(name: String, setting: (MediaSource.Factory) -> Unit) = apply {
            settings[name] = setting
        }

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) =
            keep("setDrmSessionManagerProvider") {
                it.setDrmSessionManagerProvider(provider)
            }

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) =
            keep("setLoadErrorHandlingPolicy") {
                it.setLoadErrorHandlingPolicy(policy)
            }

        override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory) =
            keep("setCmcdConfigurationFactory") {
                it.setCmcdConfigurationFactory(factory)
            }

        override fun setSubtitleParserFactory(factory: SubtitleParser.Factory) =
            keep("setSubtitleParserFactory") {
                it.setSubtitleParserFactory(factory)
            }

        @Deprecated("As MediaSource.Factory's")
        override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean) =
            keep("experimentalParseSubtitlesDuringExtraction") {
                @Suppress("DEPRECATION") it.experimentalParseSubtitlesDuringExtraction(parse)
            }

        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int) =
            keep("experimentalSetCodecsToParseWithinGopSampleDependencies") {
                it.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
            }

        override fun setDownloadExecutor(executor: Supplier<ReleasableExecutor>) =
            keep("setDownloadExecutor") {
                it.setDownloadExecutor(executor)
            }
    }

    /** What the data sources of one source know of it: whether it has the core, its playlists. */
    private inner class Link : SourceLink {
        // The latest text of each playlist, by the URL that the player asked for (`keep`'s key), in
        // the order of their first load: a master before its media playlists.
        private val playlists = LinkedHashMap<String, Playlist>()

        // Where the core places a live player of this source: see placementAfter.
        @Volatile var placement: LiveDelay? = null

        override val isServed: Boolean
            get() = served?.link === this

        /** Forgets the playlists: the source loads them again when Media3 prepares it again. */
        fun clear() {
            synchronized(playlists) {
                playlists.clear()
                placement = null
            }
        }

        override fun keep(key: String, playlist: Playlist) {
            synchronized(playlists) { playlists[key] = playlist }
        }

        // The core's answers, which come only while the source is served. A late one of an item
        // that played places only that item's source.
        override fun onManifest(manifest: ProcessedManifest) {
            synchronized(playlists) { placement = placementAfter(placement, manifest) }
        }

        /** Gives the core the playlists that the source has, while it has the core. */
        fun handOver() {
            for (playlist in synchronized(playlists) { playlists.values.toList() }) {
                if (!isServed) return
                try {
                    core.processManifest(playlist)?.let(::onManifest)
                } catch (_: InterruptedIOException) {
                    // The engine closes.
                    return
                } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                    // A defect of the core's answer costs P2P for this playlist: a thread of the
                    // library does not end the app. On a loading thread, Media3 handles it.
                }
            }
        }
    }

    /**
     * A source of the engine. Media3 prepares it ahead of its item, as the next item of a playlist
     * or a preload: the player's thread then gives the core to the current item's source
     * ([serveItem]), as HLS.js's engine readies its core for the source that it plays.
     *
     * After an error, Media3 releases the source and prepares it again: the source then keeps the
     * core, as HLS.js's engine does when it recovers. The core recovers on its own, and falls back
     * to HTTP where it must.
     */
    @OptIn(UnstableApi::class)
    private inner class CoreSource(val link: Link, source: MediaSource) :
        WrappingMediaSource(source) {
        override fun prepareSourceInternal() {
            prepared.remove(this)
            prepared.add(this)
            watch?.check()
            super.prepareSourceInternal()
        }

        // A source forgets its playlists and its placement when Media3 releases it, as when the
        // app plays a source of its own: it plays again only after Media3 prepares it again, which
        // loads its playlists again. After an error or a stop, its first live main playlist with
        // segments places it again before Media3 builds its timeline; a first playlist with no
        // segments leaves Media3's placement until one has segments. In a recovery the source
        // keeps the core, and its loads go to the core at once.
        override fun releaseSourceInternal() {
            prepared.remove(this)
            // After the release, which cancels the source's loads: a playlist that ends before it
            // is not kept for a later hand-over (one that passed its check at that moment can
            // still be; the reload at the next prepare replaces it).
            try {
                super.releaseSourceInternal()
            } finally {
                link.clear()
                watch?.check()
            }
        }

        // The core places each timeline of the source's live main playlist.
        override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
            val placed = link.placement
            refreshSourceInfo(placed?.let { PlacedTimeline(newTimeline, it) } ?: newTimeline)
        }
    }
}

/**
 * The placement after [manifest], from [placement]: a live main playlist places the player, as the
 * web adapters place theirs from the main stream; a main playlist that is not live, such as one
 * that ended, does not. One that the core gives no placement for keeps the last: one with no
 * segments, or one whose times the core cannot read (KNOWN-ISSUES.md). An audio playlist is of a
 * secondary stream, and a master playlist lists none.
 */
internal fun placementAfter(placement: LiveDelay?, manifest: ProcessedManifest): LiveDelay? {
    val main = manifest.streams.filter { it.type == StreamType.MAIN }
    return when {
        main.isEmpty() -> placement
        main.none(ProcessedStream::isLive) -> null
        else -> manifest.liveDelay ?: placement
    }
}
