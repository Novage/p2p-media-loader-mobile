package com.novage.p2pml.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import com.novage.p2pml.core.ByteRange
import com.novage.p2pml.core.Core
import com.novage.p2pml.core.CoreRequestError
import com.novage.p2pml.core.ProcessedManifest
import java.io.ByteArrayInputStream

/** A playlist as it came: its URL after redirects, the URL asked for when it differs, its text. */
internal class Playlist(val url: String, val requestedUrl: String?, val text: String)

/** Gives [playlist] to the core: see [Core.processManifest]. */
internal fun Core.processManifest(playlist: Playlist): ProcessedManifest? =
    processManifest(playlist.url, playlist.requestedUrl, playlist.text)

/**
 * What the data sources of one media source know of it (`Media3P2PEngine`'s sources): whether it
 * has the core, which only then gets its playlists and loads its segments, and where its playlists
 * go.
 */
internal interface SourceLink {
    val isServed: Boolean

    /**
     * Keeps [playlist], which the player asked for as [key] (with no blocking reload parameters),
     * for the source's hand-over.
     */
    fun keep(key: String, playlist: Playlist)

    /** What the core made of a playlist of the source. */
    fun onManifest(manifest: ProcessedManifest)
}

/**
 * The data sources of one HLS source: the playlists go through [ManifestDataSource], the segments
 * through [SegmentDataSource], and every other request to [upstream] untouched (a whitelist, as the
 * web adapters'), such as keys and steering manifests.
 */
@OptIn(UnstableApi::class)
internal class P2pHlsDataSourceFactory(
    private val core: Core,
    private val upstream: DataSource.Factory,
    private val link: SourceLink,
) : HlsDataSourceFactory {
    override fun createDataSource(dataType: Int): DataSource =
        when (dataType) {
            C.DATA_TYPE_MANIFEST -> ManifestDataSource(core, upstream.createDataSource(), link)
            C.DATA_TYPE_MEDIA -> SegmentDataSource(core, upstream.createDataSource(), link)
            else -> upstream.createDataSource()
        }
}

/**
 * Loads a playlist through [upstream], keeps it in its source ([link]), and gives it to the core
 * when the source has it, as HLS.js's engine does: with its URL after redirects, which its URIs
 * resolve against, and the URL that the player asked for. The link gets what the core made of it.
 * Media3 reports no transfer of a playlist to its bandwidth meter, so neither does this.
 */
@OptIn(UnstableApi::class)
internal class ManifestDataSource(
    private val core: Core,
    private val upstream: DataSource,
    private val link: SourceLink,
) : DataSource {
    private var data: ByteArrayInputStream? = null
    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val request = dataSpec.withoutDeltaUpdate()
        val bytes =
            try {
                upstream.open(request)
                uri = upstream.uri ?: request.uri
                headers = upstream.responseHeaders
                DataSourceUtil.readToEnd(upstream)
            } finally {
                // As Media3's own playlist loads: an error of the close does not fail the load.
                DataSourceUtil.closeQuietly(upstream)
            }
        val url = uri.toString()
        val asked = request.uri.toString()
        val playlist = Playlist(url, asked.takeIf { it != url }, String(bytes, Charsets.UTF_8))
        // Media3 interrupts the thread of a load that it cancels, as at a source's release. The
        // core loads over HTTP only, so a local playlist (a file, an asset) is not for it.
        if (!Thread.currentThread().isInterrupted && uri?.scheme?.lowercase() in HTTP_SCHEMES) {
            // Kept before the check: a hand-over that starts after the check has it. Each blocking
            // reload of LL-HLS asks for a new `_HLS_msn` and `_HLS_part`: one key keeps the latest.
            link.keep(request.uri.without(BLOCKING_RELOAD).toString(), playlist)
            if (link.isServed) core.processManifest(playlist)?.let(link::onManifest)
        }
        data = ByteArrayInputStream(bytes)
        return bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(data).readAsDataSource(buffer, offset, length)

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        data = null
        uri = null
        headers = emptyMap()
    }
}

/**
 * Loads a segment through the core when its source has the core ([link]) and the core knows it, and
 * else through [upstream], as the web adapters' segment loaders.
 *
 * The engine's bandwidth meter gets the core's bandwidth for a segment from the core. Other
 * listeners see the transfer from the start of the core's wait. Not
 * [androidx.media3.datasource.BaseDataSource]: its listeners cannot reach [upstream], which reports
 * its own transfers.
 */
@OptIn(UnstableApi::class)
internal class SegmentDataSource(
    private val core: Core,
    private val upstream: DataSource,
    private val link: SourceLink,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    // The meter's listeners, which take the core's bandwidth for its segments in place of the
    // clock.
    private val meters = mutableListOf<CoreBandwidthMeter.CoreTransferListener>()
    // The open request when it goes through the core.
    private var coreSpec: DataSpec? = null
    private var data: ByteArrayInputStream? = null
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListener is CoreBandwidthMeter.CoreTransferListener) {
            meters += transferListener
        } else {
            listeners += transferListener
        }
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val range =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) null
            else ByteRange(dataSpec.position, dataSpec.position + dataSpec.length - 1)
        // Media3 resumes a segment (a position with no length) only after a read fails. Only
        // upstream can fail in a read: the core gives the whole segment in open.
        val resumed = range == null && dataSpec.position != 0L
        val bytes =
            if (resumed || !link.isServed || !core.isSegmentLoadable(url, range)) null
            else loadThroughCore(dataSpec, url, range)
        if (bytes == null) {
            upstreamOpen = true
            return upstream.open(dataSpec)
        }
        data = ByteArrayInputStream(bytes)
        return bytes.size.toLong()
    }

    /**
     * The segment from the core, or null when the core failed or aborted it. Upstream can have what
     * the core does not, such as the app's request headers: a failure costs P2P for this request,
     * not the playback. The core keeps one load for each stream type, and aborts the open one when
     * Media3 loads two renditions of one type at once, such as two audio renditions.
     */
    private fun loadThroughCore(dataSpec: DataSpec, url: String, range: ByteRange?): ByteArray? {
        coreSpec = dataSpec
        for (listener in listeners) listener.onTransferInitializing(this, dataSpec, IS_NETWORK)
        for (listener in listeners) listener.onTransferStart(this, dataSpec, IS_NETWORK)
        return try {
            val response = core.loadSegment(url, range)
            for (meter in meters) meter.onCoreSegment(response.bandwidth, response.streamType)
            response.data
        } catch (_: CoreRequestError) {
            endCoreTransfer()
            null
        }
    }

    private fun endCoreTransfer() {
        coreSpec?.let { spec ->
            coreSpec = null
            data = null
            for (listener in listeners) listener.onTransferEnd(this, spec, IS_NETWORK)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (upstreamOpen) return upstream.read(buffer, offset, length)
        val count = checkNotNull(data).readAsDataSource(buffer, offset, length)
        if (count > 0) {
            val spec = checkNotNull(coreSpec)
            for (listener in listeners) listener.onBytesTransferred(this, spec, IS_NETWORK, count)
        }
        return count
    }

    override fun getUri(): Uri? = if (upstreamOpen) upstream.uri else coreSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (upstreamOpen) upstream.responseHeaders else emptyMap()

    // Media3 closes a data source also after a failed open.
    override fun close() {
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
        endCoreTransfer()
    }

    private companion object {
        // The core loads over the network, from peers and over HTTP.
        const val IS_NETWORK = true
    }
}

private val HTTP_SCHEMES = setOf("http", "https")

// The query parameters of an LL-HLS blocking reload.
private val BLOCKING_RELOAD = setOf("_HLS_msn", "_HLS_part")

// The query parameter of an HLS delta update.
private val DELTA_UPDATE = setOf("_HLS_skip")

/**
 * The request without `_HLS_skip`. Media3 asks for a delta update when the server offers one, and
 * the core reads a playlist as a whole one: it would number the segments of a delta wrongly. The
 * server then sends the whole playlist, which Media3 also takes. The other parameters stay as they
 * are written, as a signed URL needs.
 */
@OptIn(UnstableApi::class)
private fun DataSpec.withoutDeltaUpdate(): DataSpec {
    val whole = uri.without(DELTA_UPDATE)
    return if (whole === uri) this else buildUpon().setUri(whole).build()
}

/** The URI without the query parameters [names], or this URI when it has none of them. */
private fun Uri.without(names: Set<String>): Uri {
    val params = encodedQuery?.split('&').orEmpty()
    val kept = params.filterNot { it.substringBefore('=') in names }
    if (kept.size == params.size) return this
    return buildUpon().encodedQuery(kept.joinToString("&").ifEmpty { null }).build()
}

/**
 * Reads as `DataSource.read`: the count, or the end ([ByteArrayInputStream]'s -1 is
 * `C.RESULT_END_OF_INPUT`). A read of no bytes gives 0, also at the end.
 */
private fun ByteArrayInputStream.readAsDataSource(buffer: ByteArray, offset: Int, length: Int) =
    if (length == 0) 0 else read(buffer, offset, length)
