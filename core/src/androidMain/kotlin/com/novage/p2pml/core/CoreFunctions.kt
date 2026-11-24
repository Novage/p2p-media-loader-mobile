package com.novage.p2pml.core

import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
 * The synchronous functions of the core's settings run on the core's thread, which waits for them:
 * they must be fast, and must not wait for the core, also not through another thread, which stops
 * the core for good (specs/api.md, "Functions in the settings").
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
 * Sets up the core's HTTP request of a segment, as the JS core's `httpRequestSetup`
 * ([DynamicStreamConfig.httpRequestSetup]): a request of the app's own, such as a signed URL or its
 * headers, or null for the core's. The core calls it before each HTTP request of a segment, on a
 * thread of the library's, and waits for it as for an async JS function: it can block, but it must
 * return, such as with a timeout on its own I/O. A throw fails that request (the core tries again,
 * as for an HTTP error). It must be thread-safe: the core can call it for several requests at once.
 * Give the core one object, which reads what changes, such as a token: each new object stays with
 * the core until [Core.close].
 */
public fun interface HttpRequestSetup {
    public fun setUp(context: HttpRequestSetupContext): HttpRequest?
}

/** What a [HttpRequestSetup] gets: the segment, and the bytes that the request asks for. */
public data class HttpRequestSetupContext(
    public val segmentUrl: String,
    public val segmentByteRange: ByteRange?,
    /** The bytes to ask for, where the request does not ask for the whole file. */
    public val requestByteRange: RequestByteRange?,
)

/** The bytes from [start], to [end] inclusive, or to the end of the file when [end] is null. */
public data class RequestByteRange(public val start: Long, public val end: Long?)

/**
 * A request of an [HttpRequestSetup]: [url] with [headers]. Where it asks for part of the file
 * (`requestByteRange`), the core adds its `Range` header unless [headers] has one.
 */
public data class HttpRequest
@JvmOverloads
constructor(public val url: String, public val headers: Map<String, String> = emptyMap())

/**
 * Validates a segment from a peer, as the JS core's `validateP2PSegment`
 * ([DynamicStreamConfig.validateP2PSegment]), such as with its hash: false closes the connection to
 * that peer. The core calls it for each download that holds a peer's bytes, on a thread of the
 * library's, as [HttpRequestSetup], and must be thread-safe as it. Set while the core runs, it
 * validates the segments of the peers that connect after the change.
 */
public fun interface P2PSegmentValidator {
    public fun isValid(context: P2PSegmentContext): Boolean
}

/** What a [P2PSegmentValidator] gets: the segment and its bytes. */
public class P2PSegmentContext(
    public val url: String,
    public val byteRange: ByteRange?,
    public val data: ByteArray,
)

/**
 * The functions of a core's settings, by number, for the bridge (specs/engine.md): the settings'
 * JSON has `{"function": n}` in place of each. One function object is one number for each kind, so
 * that JS gets one function for it, as the JS core expects of a `swarmId` function shared by both
 * stream types. It takes functions while the core runs ([Core.applyDynamicConfig]): its lock guards
 * them. The async ones run on [threads] ([functionPool]); a throw there, out of the app's function,
 * goes to [failed].
 */
internal class FunctionTable(
    private val threads: ExecutorService = functionPool(),
    private val failed: (Throwable) -> Unit = {},
) {
    private val calls = ArrayList<(JSONObject, ByteArray?) -> Any?>()
    private val objects = ArrayList<Any>()
    private val swarmIds = IdentityHashMap<SwarmIdFunction, Int>()
    private val builders = IdentityHashMap<StreamIdBuilder, Int>()
    private val filters = IdentityHashMap<SegmentFilter, Int>()
    private val setups = IdentityHashMap<HttpRequestSetup, Int>()
    private val validators = IdentityHashMap<P2PSegmentValidator, Int>()

    fun marker(function: SwarmIdFunction) =
        marker(swarmIds, function) { json, _ ->
            // A Java function can give null, which JS would read as no swarmId at all.
            val swarmId: String? = function.swarmId(json.toSwarmIdContext())
            requireNotNull(swarmId) { "swarmIdFunction gave null" }
        }

    fun marker(function: StreamIdBuilder) =
        marker(builders, function) { json, _ ->
            function.streamId(json.toStreamIdBuilderContext())
        }

    fun marker(function: SegmentFilter) =
        marker(filters, function) { json, _ -> function.isShared(json.toSegmentFilterContext()) }

    fun marker(function: HttpRequestSetup) =
        marker(setups, function) { json, _ ->
            function.setUp(json.toHttpRequestSetupContext())?.let { request ->
                JSONObject().put("url", request.url).put("headers", JSONObject(request.headers))
            }
        }

    fun marker(function: P2PSegmentValidator) =
        marker(validators, function) { json, data ->
            function.isValid(
                P2PSegmentContext(json.text("url"), json.byteRangeOf(), data ?: ByteArray(0))
            )
        }

    private fun <F : Any> marker(
        numbers: IdentityHashMap<F, Int>,
        function: F,
        call: (JSONObject, ByteArray?) -> Any?,
    ): JSONObject {
        val number =
            synchronized(this) {
                numbers.getOrPut(function) {
                    calls.add(call)
                    objects.add(function)
                    calls.lastIndex
                }
            }
        return JSONObject().put("function", number)
    }

    /** The function object of [marker], `{"function": n}`; null for any other value. */
    fun functionOf(marker: Any?): Any? =
        ((marker as? JSONObject)?.value("function") as? Number)?.let {
            synchronized(this) { objects.getOrNull(it.toInt()) }
        }

    /**
     * Calls function [number] with its argument as [json]: `{"value": v}`, or `{"error": message}`
     * when it throws, with the throw's message (or its class, when it has none or a blank one);
     * `{"error": "no such function"}` for a number it does not have.
     */
    fun call(number: Int, json: String): String = answerOf(number, json, null)

    /**
     * Calls async function [number] with its argument as [json] and [data] on one of its threads,
     * and gives its answer, as [call]'s, to [answer] there. Nothing after [close].
     */
    fun callAsync(number: Int, json: String, data: ByteArray?, answer: (String) -> Unit) {
        try {
            threads.execute {
                // A throw here, such as an OutOfMemoryError, must not end the library's thread,
                // and with it the app. JS then has no answer: a setup's request waits for the
                // core's timeout, and a validation, which has none, until the player aborts it.
                try {
                    answer(answerOf(number, json, data))
                } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                    runCatching { failed(e) }
                }
            }
        } catch (_: RejectedExecutionException) {
            // The core is closed: JS takes no answer.
        }
    }

    /**
     * Takes no more async calls. No interrupt: it would reach the app's own I/O in a function that
     * runs, whose answer the closed core drops anyway.
     */
    fun close() {
        threads.shutdown()
    }

    private fun answerOf(number: Int, json: String, data: ByteArray?): String {
        val call = synchronized(this) { calls.getOrNull(number) } ?: return NO_FUNCTION
        val answer = JSONObject()
        try {
            answer.put("value", call(JSONObject(json), data) ?: JSONObject.NULL)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Any throw goes to JS, as a throw of a JS function would: an Error too (such as
            // TODO()), which would end a thread of the library, and with it the app. The text of
            // an app's exception can throw too: then its class.
            val name = e.javaClass.name
            answer.put(
                "error",
                runCatching { e.message?.takeUnless { it.isBlank() } ?: name }.getOrDefault(name),
            )
        }
        return answer.toString()
    }

    private companion object {
        val NO_FUNCTION = JSONObject().put("error", "no such function").toString()
    }
}

/**
 * The threads of the async functions: one for each call at once, which ends when idle. A function
 * must return, such as with a timeout on its own I/O: one that never returns holds its thread.
 */
internal fun functionPool(): ExecutorService = Executors.newCachedThreadPool { task ->
    Thread(task, "p2pml-functions").apply { isDaemon = true }
}

private fun JSONObject.toHttpRequestSetupContext() =
    HttpRequestSetupContext(
        segmentUrl = text("segmentUrl"),
        segmentByteRange = byteRangeOf("segmentByteRange"),
        requestByteRange =
            optJSONObject("requestByteRange")?.let {
                RequestByteRange(it.optLong("start"), (it.value("end") as? Number)?.toLong())
            },
    )

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
