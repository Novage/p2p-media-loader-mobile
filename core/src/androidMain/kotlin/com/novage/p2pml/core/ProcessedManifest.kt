package com.novage.p2pml.core

import org.json.JSONObject

/**
 * What the core made of a manifest, as the JS core's `ProcessedManifest`: one entry for each stream
 * that it listed segments for. A master playlist lists none; a media playlist lists one.
 *
 * [liveDelay] is not in the JS shape: it is what the JS core's `liveDelayFor` gives for this
 * manifest, so that the core stays the one source of its placement rules (specs/api.md).
 */
public data class ProcessedManifest(
    public val streams: List<ProcessedStream>,
    /** Where to place a live player, or null when the manifest has no live stream with segments. */
    public val liveDelay: LiveDelay?,
)

/** A stream of a [ProcessedManifest], as the JS core's `ProcessedStream`. Times are in seconds. */
public data class ProcessedStream(
    /** The stream key: the media playlist URL for HLS. */
    public val key: String,
    public val type: StreamType,
    public val isLive: Boolean,
    /**
     * The start of the earliest listed segment, on the stream's manifest timeline; NaN as in JS.
     */
    public val start: Double,
    /** The end of the latest listed segment, on the same timeline; NaN as in JS. */
    public val end: Double,
    /** How many segments the stream lists now. */
    public val segmentCount: Int,
    /** The live window that the manifest declares, where its span is not a stable measure. */
    public val declaredWindow: Double?,
)

/**
 * Where a live player goes, as the JS core's `LiveDelay`, with the core's rules for that placement.
 * All are in seconds.
 */
public data class LiveDelay(
    /** How far behind the live edge to place the playhead. */
    public val delay: Double,
    /** The average segment length that the delay comes from. */
    public val segment: Double,
    /** How far ahead of the playhead the player may load: the JS core's `playerBufferFor`. */
    public val playerBuffer: Double,
    /**
     * How far behind the live edge the player may fall before it goes back to [delay]: the JS
     * core's `maxLiveLatencyFor`.
     */
    public val maxLiveLatency: Double,
)

internal fun JSONObject.toProcessedManifest(): ProcessedManifest {
    val streams = getJSONArray("streams")
    return ProcessedManifest(
        List(streams.length()) { streams.getJSONObject(it).toProcessedStream() },
        optJSONObject("liveDelay")?.let {
            LiveDelay(
                it.getDouble("delay"),
                it.getDouble("segment"),
                it.getDouble("playerBuffer"),
                it.getDouble("maxLiveLatency"),
            )
        },
    )
}

private fun JSONObject.toProcessedStream() =
    ProcessedStream(
        key = getString("key"),
        type = if (getString("type") == "main") StreamType.MAIN else StreamType.SECONDARY,
        isLive = getBoolean("isLive"),
        // NaN in JS, which JSON gives as null: a date that the engine's Date cannot read, as
        // "+01", makes the times of the segments NaN.
        start = optDouble("start"),
        end = optDouble("end"),
        segmentCount = getInt("segmentCount"),
        declaredWindow = (value("declaredWindow") as? Number)?.toDouble(),
    )
