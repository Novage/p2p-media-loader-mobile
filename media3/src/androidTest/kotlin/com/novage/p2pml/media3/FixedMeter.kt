package com.novage.p2pml.media3

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import java.util.concurrent.ConcurrentLinkedQueue

// A bandwidth meter with a fixed estimate, which notes the transfers that end.
@OptIn(UnstableApi::class)
internal class FixedMeter : BandwidthMeter {
    val ends = ConcurrentLinkedQueue<DataSpec>()

    private val listener =
        object : TransferListener {
            override fun onTransferInitializing(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) = Unit

            override fun onTransferStart(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) = Unit

            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) = Unit

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                ends += dataSpec
            }
        }

    override fun getBitrateEstimate() = BITRATE

    override fun getTransferListener() = listener

    override fun addEventListener(
        eventHandler: Handler,
        eventListener: BandwidthMeter.EventListener,
    ) = Unit

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit

    companion object {
        const val BITRATE = 1_000_000L
    }
}
