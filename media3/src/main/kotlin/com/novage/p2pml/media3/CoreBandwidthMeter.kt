package com.novage.p2pml.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.novage.p2pml.core.StreamType

/**
 * The player's bandwidth meter. The core is its source: the estimate is the bandwidth that the core
 * gave with its last segment of the main stream, as the web adapters give it to their players. The
 * core measures its downloads, HTTP and P2P; the clock says nothing about the network for a segment
 * from a peer or from the core's store, which can come at once. Until the core gives a bandwidth
 * (no core, P2P off, or no main segment yet), the estimate is [inner]'s, which measures the
 * transfers by the clock.
 */
@OptIn(UnstableApi::class)
internal class CoreBandwidthMeter(private val inner: BandwidthMeter) : BandwidthMeter by inner {
    private val listener = CoreTransferListener()
    // The core's last bandwidth, in bits per second; 0 before it gives one.
    @Volatile private var coreBandwidth = 0L

    override fun getBitrateEstimate(): Long =
        coreBandwidth.takeIf { it > 0 } ?: inner.bitrateEstimate

    // A Java default method: Kotlin's delegation does not forward it.
    override fun getTimeToFirstByteEstimateUs(): Long = inner.timeToFirstByteEstimateUs

    override fun getTransferListener(): CoreTransferListener = listener

    /**
     * The meter's listener: the data sources give it the core's bandwidth for a segment from the
     * core, and report every other transfer to it, which goes to [inner] for the time before the
     * core gives a bandwidth.
     */
    inner class CoreTransferListener : TransferListener {
        /**
         * A segment of a [streamType] stream came from the core, which measured [bandwidth], in
         * bits per second. The core measures each stream type in its own way: as HLS.js, the meter
         * counts the main stream only.
         */
        fun onCoreSegment(bandwidth: Double, streamType: StreamType) {
            // The core gives 0 when it has no measure yet.
            if (streamType == StreamType.MAIN && bandwidth > 0) coreBandwidth = bandwidth.toLong()
        }

        override fun onTransferInitializing(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
        ) {
            inner.transferListener?.onTransferInitializing(source, dataSpec, isNetwork)
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            inner.transferListener?.onTransferStart(source, dataSpec, isNetwork)
        }

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            inner.transferListener?.onBytesTransferred(
                source,
                dataSpec,
                isNetwork,
                bytesTransferred,
            )
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            inner.transferListener?.onTransferEnd(source, dataSpec, isNetwork)
        }
    }
}
