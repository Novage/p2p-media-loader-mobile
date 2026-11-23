package com.novage.p2pml.core

import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Names the swarm of each source, as the JS core's `swarmId` when it is a function
 * ([StreamConfig.swarmIdFunction]). The core calls it once for each source, when the source
 * registers its first stream, and again for the next source after [Core.destroy]; after a throw,
 * the next stream of the source asks again. It must give every viewer of the same content the same
 * non-empty text; a throw or an empty text fails the stream's registration
 * ([CoreEvent.OnStreamRegistrationError]), and the stream plays without P2P.
 *
 * The functions of the core's settings run on the core's thread, which waits for them: they must be
 * fast, and must not wait for the core, also not through another thread, which stops the core for
 * good (specs/api.md, "Functions in the settings").
 */
public fun interface SwarmIdFunction {
    public fun swarmId(context: SwarmIdContext): String
}

/**
 * Builds a stream's ID, as the JS core's `streamIdBuilder` ([StreamConfig.streamIdBuilder]): called
 * once for each stream when the core registers it. Null keeps
 * [StreamIdBuilderContext.defaultStreamId]. It must give every peer of the swarm the same ID for a
 * stream, and each stream its own. A throw or an empty text fails the stream's registration. It
 * runs as [SwarmIdFunction] says.
 */
public fun interface StreamIdBuilder {
    public fun streamId(context: StreamIdBuilderContext): String?
}

/**
 * Keeps chosen segments off P2P, as the JS core's `segmentFilter` ([StreamConfig.segmentFilter]):
 * true shares the segment, false keeps it off P2P, and so does a throw. The core asks each time a
 * manifest lists the segment, also at each refresh of a live playlist. It runs as [SwarmIdFunction]
 * says.
 */
public fun interface SegmentFilter {
    public fun isShared(context: SegmentFilterContext): Boolean
}

/**
 * What a [SwarmIdFunction] gets, as the JS core's `SwarmIdContext`: the URL that would name the
 * swarm with no `swarmId` (the first manifest's requested URL, or the one that
 * [Core.setManifestResponseUrl] gave, without its query), and the same URL with its query.
 */
public data class SwarmIdContext(public val manifestUrl: String, public val requestedUrl: String)

/** What a [StreamIdBuilder] gets, as the JS core's `StreamIdBuilderContext`. */
public data class StreamIdBuilderContext(
    /** The stream's swarm ID: do not repeat it in the stream ID. */
    public val swarmId: String,
    /** The stream key: the media playlist's URL for HLS. */
    public val runtimeId: String,
    public val streamType: StreamType,
    public val properties: StreamProperties,
    /** The stream ID that the core uses for null, such as `main0`. */
    public val defaultStreamId: String,
    /** HLS: the stream's index among the master's streams of its type; else null. */
    public val index: Int?,
    /** The manifest that declared the stream; null for a media playlist with no master. */
    public val manifest: ManifestInfo?,
)

/** The manifest of a [StreamIdBuilderContext]: its requested URL, its text and its streams. */
public data class ManifestInfo(
    public val url: String,
    public val text: String,
    public val streams: List<ManifestStreamInfo>,
)

/** A stream of a [ManifestInfo], as the JS core's `ManifestStreamInfo`. */
public data class ManifestStreamInfo(
    public val runtimeId: String,
    public val streamType: StreamType,
    public val defaultStreamId: String,
    public val index: Int?,
    public val properties: StreamProperties,
)

/** What a [SegmentFilter] gets, as the JS core's `SegmentFilterContext`. */
public data class SegmentFilterContext(
    public val streamType: StreamType,
    /** The stream key: the media playlist's URL for HLS. */
    public val runtimeId: String,
    /** The segment's URL, as the player requests it. */
    public val url: String,
    public val byteRange: ByteRange?,
    /** Where the segment starts and ends on the stream's timeline, in seconds. */
    public val startTime: Double,
    public val endTime: Double,
    /** HLS: the tags before the segment that can mark an ad break; null where there are none. */
    public val hls: HlsSegmentMarkers?,
    /** DASH: where the segment's period starts, where known. */
    public val dash: DashSegmentMarkers?,
)

/**
 * The tags before an HLS segment that can mark an ad break, as the JS core's `HlsSegmentMarkers`:
 * each cue's text after the tag's colon, empty where the tag has none, null where there is no tag.
 */
public data class HlsSegmentMarkers(
    public val discontinuity: Boolean,
    public val cueOut: String?,
    public val cueOutCont: String?,
    public val cueIn: String?,
)

/** Where a DASH segment's period starts on the presentation timeline, in seconds. */
public data class DashSegmentMarkers(public val periodStart: Double)

/**
 * The functions of a core's settings, by number, for the bridge (specs/engine.md): the settings'
 * JSON has `{"function": n}` in place of each. One function object is one number for each kind, so
 * that JS gets one function for it, as the JS core expects of a `swarmId` function shared by both
 * stream types.
 */
internal class FunctionTable {
    private val calls = ArrayList<(JSONObject) -> Any?>()
    private val objects = ArrayList<Any>()
    private val swarmIds = IdentityHashMap<SwarmIdFunction, Int>()
    private val builders = IdentityHashMap<StreamIdBuilder, Int>()
    private val filters = IdentityHashMap<SegmentFilter, Int>()

    fun marker(function: SwarmIdFunction) =
        marker(swarmIds, function) {
            // A Java function can give null, which JS would read as no swarmId at all.
            val swarmId: String? = function.swarmId(it.toSwarmIdContext())
            requireNotNull(swarmId) { "swarmIdFunction gave null" }
        }

    fun marker(function: StreamIdBuilder) =
        marker(builders, function) { function.streamId(it.toStreamIdBuilderContext()) }

    fun marker(function: SegmentFilter) =
        marker(filters, function) { function.isShared(it.toSegmentFilterContext()) }

    private fun <F : Any> marker(
        numbers: IdentityHashMap<F, Int>,
        function: F,
        call: (JSONObject) -> Any?,
    ): JSONObject {
        val number =
            numbers.getOrPut(function) {
                calls.add(call)
                objects.add(function)
                calls.lastIndex
            }
        return JSONObject().put("function", number)
    }

    /** The function object of [marker], `{"function": n}`; null for any other value. */
    fun functionOf(marker: Any?): Any? =
        ((marker as? JSONObject)?.value("function") as? Number)?.let {
            objects.getOrNull(it.toInt())
        }

    /**
     * Calls function [number] with its argument as [json]: `{"value": v}`, or `{"error": message}`
     * when it throws, with the throw's message (or its class, when it has none or a blank one);
     * null for a number it does not have.
     */
    fun call(number: Int, json: String): String? {
        val call = calls.getOrNull(number) ?: return null
        val answer = JSONObject()
        try {
            answer.put("value", call(JSONObject(json)) ?: JSONObject.NULL)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Any throw goes to JS, as a throw of a JS function would: an Error too (such as
            // TODO()), which would end the engine's thread, and with it the app. The text of an
            // app's exception can throw too: then its class.
            val name = e.javaClass.name
            answer.put(
                "error",
                runCatching { e.message?.takeUnless { it.isBlank() } ?: name }.getOrDefault(name),
            )
        }
        return answer.toString()
    }
}

private fun JSONObject.toSwarmIdContext() =
    SwarmIdContext(text("manifestUrl"), text("requestedUrl"))

private fun JSONObject.toStreamIdBuilderContext() =
    StreamIdBuilderContext(
        swarmId = text("swarmId"),
        runtimeId = text("runtimeId"),
        streamType = streamType("streamType"),
        properties = propertiesOf(),
        defaultStreamId = text("defaultStreamId"),
        index = (value("index") as? Number)?.toInt(),
        manifest =
            optJSONObject("manifest")?.let { manifest ->
                val streams = manifest.optJSONArray("streams") ?: JSONArray()
                ManifestInfo(
                    manifest.text("url"),
                    manifest.text("text"),
                    List(streams.length()) { streams.getJSONObject(it).toManifestStreamInfo() },
                )
            },
    )

private fun JSONObject.toManifestStreamInfo() =
    ManifestStreamInfo(
        runtimeId = text("runtimeId"),
        streamType = streamType("streamType"),
        defaultStreamId = text("defaultStreamId"),
        index = (value("index") as? Number)?.toInt(),
        properties = propertiesOf(),
    )

private fun JSONObject.toSegmentFilterContext() =
    SegmentFilterContext(
        streamType = streamType("streamType"),
        runtimeId = text("runtimeId"),
        url = text("url"),
        byteRange = byteRangeOf(),
        startTime = optDouble("startTime"),
        endTime = optDouble("endTime"),
        hls =
            optJSONObject("hls")?.let {
                HlsSegmentMarkers(
                    it.optBoolean("discontinuity"),
                    it.textOrNull("cueOut"),
                    it.textOrNull("cueOutCont"),
                    it.textOrNull("cueIn"),
                )
            },
        dash = optJSONObject("dash")?.let { DashSegmentMarkers(it.optDouble("periodStart")) },
    )
