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
import java.io.ByteArrayInputStream

/**
 * The data sources of HLS: the playlists go through [ManifestDataSource], the segments through
 * [SegmentDataSource], and every other request to [upstream] untouched (a whitelist, as the web
 * adapters'), such as keys and steering manifests.
 */
@OptIn(UnstableApi::class)
internal class P2pHlsDataSourceFactory(
    private val core: Core,
    private val upstream: DataSource.Factory,
) : HlsDataSourceFactory {
    override fun createDataSource(dataType: Int): DataSource =
        when (dataType) {
            C.DATA_TYPE_MANIFEST -> ManifestDataSource(core, upstream.createDataSource())
            C.DATA_TYPE_MEDIA -> SegmentDataSource(core, upstream.createDataSource())
            else -> upstream.createDataSource()
        }
}

/**
 * Loads a playlist through [upstream] and gives it to the core, as HLS.js's engine does: with its
 * URL after redirects, which its URIs resolve against, and the URL that the player asked for.
 * Media3 reports no transfer of a playlist to its bandwidth meter, so neither does this.
 */
@OptIn(UnstableApi::class)
internal class ManifestDataSource(private val core: Core, private val upstream: DataSource) :
    DataSource {
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
        val requestedUrl = request.uri.toString().takeIf { it != url }
        val text = String(bytes, Charsets.UTF_8)
        // Media3 interrupts the thread of a load that it cancels, as at a source's release. Such a
        // playlist must not reach the core, which may already serve the next source. The core
        // loads over HTTP only, so a local playlist (a file, an asset) is not for it.
        val forCore =
            !Thread.currentThread().isInterrupted && uri?.scheme?.lowercase() in HTTP_SCHEMES
        if (forCore) core.processManifest(url, requestedUrl, text)
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
 * Loads a segment through the core when the core knows it, and else through [upstream], as the web
 * adapters' segment loaders.
 *
 * The engine's bandwidth meter gets the core's bandwidth for a segment from the core. Other
 * listeners see the transfer from the start of the core's wait. Not
 * [androidx.media3.datasource.BaseDataSource]: its listeners cannot reach [upstream], which reports
 * its own transfers.
 */
@OptIn(UnstableApi::class)
internal class SegmentDataSource(private val core: Core, private val upstream: DataSource) :
    DataSource {
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
            if (resumed || !core.isSegmentLoadable(url, range)) null
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

/**
 * The request without `_HLS_skip`. Media3 asks for a delta update when the server offers one, and
 * the core reads a playlist as a whole one: it would number the segments of a delta wrongly. The
 * server then sends the whole playlist, which Media3 also takes. The other parameters stay as they
 * are written, as a signed URL needs.
 */
@OptIn(UnstableApi::class)
private fun DataSpec.withoutDeltaUpdate(): DataSpec {
    val params = uri.encodedQuery?.split('&').orEmpty()
    val kept = params.filterNot { it.substringBefore('=') == "_HLS_skip" }
    if (kept.size == params.size) return this
    val query = kept.joinToString("&").ifEmpty { null }
    return buildUpon().setUri(uri.buildUpon().encodedQuery(query).build()).build()
}

/**
 * Reads as `DataSource.read`: the count, or the end ([ByteArrayInputStream]'s -1 is
 * `C.RESULT_END_OF_INPUT`). A read of no bytes gives 0, also at the end.
 */
private fun ByteArrayInputStream.readAsDataSource(buffer: ByteArray, offset: Int, length: Int) =
    if (length == 0) 0 else read(buffer, offset, length)
