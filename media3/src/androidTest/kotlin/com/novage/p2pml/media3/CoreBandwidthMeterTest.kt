package com.novage.p2pml.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.novage.p2pml.core.StreamType
import kotlin.test.Test
import kotlin.test.assertEquals

// The engine's bandwidth meter, around a meter with a fixed estimate.
@OptIn(UnstableApi::class)
class CoreBandwidthMeterTest {
    private val inner = FixedMeter()
    private val meter = CoreBandwidthMeter(inner)
    private val listener = meter.transferListener
    private val source: DataSource = DefaultHttpDataSource.Factory().createDataSource()
    private val spec = DataSpec(Uri.parse("https://example.com/seg.ts"))

    @Test
    fun theCoreIsTheSourceOnceItGivesABandwidth() {
        assertEquals(FixedMeter.BITRATE, meter.bitrateEstimate, "before the core gives one")
        listener.onCoreSegment(CORE_BITRATE.toDouble(), StreamType.MAIN)
        assertEquals(CORE_BITRATE, meter.bitrateEstimate, "after a segment from the core")
        listener.onTransferEnd(source, spec, true)
        assertEquals(CORE_BITRATE, meter.bitrateEstimate, "after a transfer of upstream")
        assertEquals(listOf(spec), inner.ends.toList(), "the inner meter got the transfer")
    }

    @Test
    fun aCoreSegmentWithNoMeasureChangesNothing() {
        listener.onCoreSegment(0.0, StreamType.MAIN)
        assertEquals(FixedMeter.BITRATE, meter.bitrateEstimate)
    }

    @Test
    fun aSecondaryStreamsBandwidthDoesNotCount() {
        listener.onCoreSegment(CORE_BITRATE.toDouble(), StreamType.MAIN)
        listener.onCoreSegment(AUDIO_BITRATE.toDouble(), StreamType.SECONDARY)
        assertEquals(CORE_BITRATE, meter.bitrateEstimate)
    }

    private companion object {
        const val CORE_BITRATE = 5_000_000L
        const val AUDIO_BITRATE = 9_000_000L
    }
}
