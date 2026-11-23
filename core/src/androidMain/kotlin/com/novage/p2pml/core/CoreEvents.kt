package com.novage.p2pml.core

import org.json.JSONObject

/**
 * An event of the core, as a name of the JS core's `CoreEventMap`, with the type of its details:
 * the key of [Core.addEventListener]. The objects have the JS names.
 */
public sealed class CoreEvent<T>(internal val name: String, private val parse: (JSONObject) -> T) {
    /** The details of the event from the bridge's [json]. */
    internal fun detailsOf(json: String): T = parse(JSONObject(json))

    /** A stream was registered from a manifest. */
    public data object OnStreamAdded :
        CoreEvent<StreamAddedDetails>(
            "onStreamAdded",
            { StreamAddedDetails(it.getJSONObject("stream").toStream()) },
        )

    /** A stream of a manifest could not be registered. */
    public data object OnStreamRegistrationError :
        CoreEvent<StreamRegistrationErrorDetails>(
            "onStreamRegistrationError",
            {
                StreamRegistrationErrorDetails(
                    it.text("runtimeId"),
                    it.streamType("streamType"),
                    it.propertiesOf(),
                    it.error("error"),
                )
            },
        )

    /** The player asked for a segment that the core does not know. */
    public data object OnSegmentRegistryMiss :
        CoreEvent<SegmentRegistryMissDetails>(
            "onSegmentRegistryMiss",
            { SegmentRegistryMissDetails(it.text("url"), it.byteRangeOf()) },
        )

    /** A segment download started. */
    public data object OnSegmentStart :
        CoreEvent<SegmentStartDetails>(
            "onSegmentStart",
            {
                SegmentStartDetails(
                    it.segment(),
                    it.downloadSource(),
                    it.textOrNull("peerId"),
                    it.text("infoHash"),
                    it.streamType("streamType"),
                )
            },
        )

    /** A segment was downloaded. */
    public data object OnSegmentLoaded :
        CoreEvent<SegmentLoadDetails>(
            "onSegmentLoaded",
            {
                SegmentLoadDetails(
                    it.segment(),
                    it.downloadSource(),
                    it.textOrNull("peerId"),
                    it.text("infoHash"),
                    it.streamType("streamType"),
                    it.optLong("bytesLength"),
                )
            },
        )

    /** A segment download failed. */
    public data object OnSegmentError :
        CoreEvent<SegmentErrorDetails>(
            "onSegmentError",
            {
                SegmentErrorDetails(
                    it.segment(),
                    it.downloadSource(),
                    it.textOrNull("peerId"),
                    it.text("infoHash"),
                    it.streamType("streamType"),
                    it.error("error"),
                )
            },
        )

    /** A segment download was aborted. */
    public data object OnSegmentAbort :
        CoreEvent<SegmentAbortDetails>(
            "onSegmentAbort",
            {
                SegmentAbortDetails(
                    it.segment(),
                    if (it.value("downloadSource") != null) it.downloadSource() else null,
                    it.textOrNull("peerId"),
                    it.text("infoHash"),
                    it.streamType("streamType"),
                )
            },
        )

    /** A peer connected. */
    public data object OnPeerConnect : CoreEvent<PeerDetails>("onPeerConnect", { it.peerDetails() })

    /** A connection to a peer failed. */
    public data object OnPeerConnectError :
        CoreEvent<PeerConnectErrorDetails>(
            "onPeerConnectError",
            { json ->
                json.peerDetails().run {
                    PeerConnectErrorDetails(
                        peerId,
                        infoHash,
                        streamType,
                        trackerUrl,
                        json.error("error"),
                    )
                }
            },
        )

    /** A peer's connection closed. */
    public data object OnPeerClose : CoreEvent<PeerDetails>("onPeerClose", { it.peerDetails() })

    /** A peer failed. */
    public data object OnPeerError :
        CoreEvent<PeerErrorDetails>(
            "onPeerError",
            { json ->
                json.peerDetails().run {
                    PeerErrorDetails(peerId, infoHash, streamType, trackerUrl, json.error("error"))
                }
            },
        )

    /** A peer did something wrong that the core allows a few times. */
    public data object OnPeerWarning :
        CoreEvent<PeerWarningDetails>(
            "onPeerWarning",
            { json ->
                json.peerDetails().run {
                    PeerWarningDetails(
                        peerId,
                        infoHash,
                        streamType,
                        trackerUrl,
                        json.error("warning"),
                    )
                }
            },
        )

    /** A chunk of a segment arrived. JS gives its details as arguments; here they are an object. */
    public data object OnChunkDownloaded :
        CoreEvent<ChunkDownloadedDetails>(
            "onChunkDownloaded",
            {
                ChunkDownloadedDetails(
                    it.optLong("bytesLength"),
                    it.downloadSource(),
                    it.textOrNull("peerId"),
                    it.streamType("streamType"),
                    it.text("infoHash"),
                )
            },
        )

    /** A chunk of a segment went to a peer. JS gives its details as arguments. */
    public data object OnChunkUploaded :
        CoreEvent<ChunkUploadedDetails>(
            "onChunkUploaded",
            {
                ChunkUploadedDetails(
                    it.optLong("bytesLength"),
                    it.text("peerId"),
                    it.streamType("streamType"),
                    it.text("infoHash"),
                )
            },
        )

    /** A tracker failed. */
    public data object OnTrackerError :
        CoreEvent<TrackerErrorDetails>(
            "onTrackerError",
            {
                TrackerErrorDetails(
                    it.text("trackerUrl"),
                    it.text("infoHash"),
                    it.streamType("streamType"),
                    it.error("error"),
                )
            },
        )

    /** A tracker gave a warning. */
    public data object OnTrackerWarning :
        CoreEvent<TrackerWarningDetails>(
            "onTrackerWarning",
            {
                TrackerWarningDetails(
                    it.text("trackerUrl"),
                    it.text("infoHash"),
                    it.streamType("streamType"),
                    it.error("warning"),
                )
            },
        )
}

/** Takes the details of an event: a listener of [Core.addEventListener]. */
public fun interface CoreEventListener<T> {
    public fun onEvent(details: T)
}

/** Where a segment or a chunk came from, as the JS core's `DownloadSource`. */
public enum class DownloadSource {
    HTTP,
    P2P,
}

/** A segment, as the JS core's `Segment`. Times are in seconds. */
public data class Segment(
    public val runtimeId: String,
    public val externalId: Long,
    public val url: String,
    public val byteRange: ByteRange?,
    public val startTime: Double,
    public val endTime: Double,
)

/**
 * What the manifest says of a stream, as the JS core's `StreamProperties`: null where it says
 * nothing. [channels] and [frameRate] are text, as a manifest writes them.
 */
public data class StreamProperties(
    public val bitrate: Double?,
    public val codecs: String?,
    public val width: Int?,
    public val height: Int?,
    public val language: String?,
    public val channels: String?,
    public val name: String?,
    public val frameRate: String?,
    public val videoRange: String?,
)

/** A stream that the core registered, as the JS core's `Stream`. */
public data class Stream(
    public val runtimeId: String,
    public val type: StreamType,
    public val properties: StreamProperties,
    public val swarmId: String,
    public val streamId: String,
    public val streamSwarmId: String,
    public val infoHash: String,
)

/**
 * An error or a warning of an event, as the JS core's error classes (`RequestError`, `PeerError`,
 * `PeerWarning`, `PeerConnectError`, `TrackerError`, `TrackerWarning`, or an `Error`): [name] is
 * the class, [type] the kind (null for an `Error`), and [timestamp] the time of a `RequestError`,
 * in milliseconds of the engine's clock.
 */
public data class EventError(
    public val name: String,
    public val type: String?,
    public val message: String,
    public val timestamp: Double?,
    public val cause: EventError?,
)

public data class StreamAddedDetails(public val stream: Stream)

public data class StreamRegistrationErrorDetails(
    public val runtimeId: String,
    public val streamType: StreamType,
    public val properties: StreamProperties,
    public val error: EventError,
)

public data class SegmentRegistryMissDetails(
    public val url: String,
    public val byteRange: ByteRange?,
)

public data class SegmentStartDetails(
    public val segment: Segment,
    public val downloadSource: DownloadSource,
    public val peerId: String?,
    public val infoHash: String,
    public val streamType: StreamType,
)

public data class SegmentLoadDetails(
    public val segment: Segment,
    public val downloadSource: DownloadSource,
    public val peerId: String?,
    public val infoHash: String,
    public val streamType: StreamType,
    public val bytesLength: Long,
)

public data class SegmentErrorDetails(
    public val segment: Segment,
    public val downloadSource: DownloadSource,
    public val peerId: String?,
    public val infoHash: String,
    public val streamType: StreamType,
    public val error: EventError,
)

/** [downloadSource] is null when the download had not started. */
public data class SegmentAbortDetails(
    public val segment: Segment,
    public val downloadSource: DownloadSource?,
    public val peerId: String?,
    public val infoHash: String,
    public val streamType: StreamType,
)

public data class PeerDetails(
    public val peerId: String,
    public val infoHash: String,
    public val streamType: StreamType,
    public val trackerUrl: String,
)

public data class PeerConnectErrorDetails(
    public val peerId: String,
    public val infoHash: String,
    public val streamType: StreamType,
    public val trackerUrl: String,
    public val error: EventError,
)

public data class PeerErrorDetails(
    public val peerId: String,
    public val infoHash: String,
    public val streamType: StreamType,
    public val trackerUrl: String,
    public val error: EventError,
)

public data class PeerWarningDetails(
    public val peerId: String,
    public val infoHash: String,
    public val streamType: StreamType,
    public val trackerUrl: String,
    public val warning: EventError,
)

public data class ChunkDownloadedDetails(
    public val bytesLength: Long,
    public val downloadSource: DownloadSource,
    public val peerId: String?,
    public val streamType: StreamType,
    public val infoHash: String,
)

public data class ChunkUploadedDetails(
    public val bytesLength: Long,
    public val peerId: String,
    public val streamType: StreamType,
    public val infoHash: String,
)

public data class TrackerErrorDetails(
    public val trackerUrl: String,
    public val infoHash: String,
    public val streamType: StreamType,
    public val error: EventError,
)

public data class TrackerWarningDetails(
    public val trackerUrl: String,
    public val infoHash: String,
    public val streamType: StreamType,
    public val warning: EventError,
)

// The details come from the bridge's JSON (native/src/polyfills/player.js). A field that JS left
// undefined is absent: null here, or the empty text where the core always gives one.

internal fun JSONObject.text(name: String): String = textOrNull(name).orEmpty()

internal fun JSONObject.textOrNull(name: String): String? = value(name)?.toString()

internal fun JSONObject.streamType(name: String) = streamTypeOf(optString(name))

private fun JSONObject.downloadSource() =
    if (optString("downloadSource") == "p2p") DownloadSource.P2P else DownloadSource.HTTP

internal fun JSONObject.byteRangeOf(): ByteRange? =
    optJSONObject("byteRange")?.let { ByteRange(it.optLong("start"), it.optLong("end")) }

private fun JSONObject.segment(): Segment =
    getJSONObject("segment").let {
        Segment(
            it.text("runtimeId"),
            it.optLong("externalId"),
            it.text("url"),
            it.byteRangeOf(),
            it.optDouble("startTime"),
            it.optDouble("endTime"),
        )
    }

internal fun JSONObject.toStream() =
    Stream(
        text("runtimeId"),
        streamType("type"),
        propertiesOf(),
        text("swarmId"),
        text("streamId"),
        text("streamSwarmId"),
        text("infoHash"),
    )

internal fun JSONObject.propertiesOf(): StreamProperties {
    val properties = optJSONObject("properties") ?: JSONObject()
    fun number(name: String) = properties.value(name) as? Number
    return StreamProperties(
        bitrate = number("bitrate")?.toDouble(),
        codecs = properties.textOrNull("codecs"),
        width = number("width")?.toInt(),
        height = number("height")?.toInt(),
        language = properties.textOrNull("language"),
        channels = properties.textOrNull("channels"),
        name = properties.textOrNull("name"),
        frameRate = properties.textOrNull("frameRate"),
        videoRange = properties.textOrNull("videoRange"),
    )
}

private fun JSONObject.peerDetails() =
    PeerDetails(text("peerId"), text("infoHash"), streamType("streamType"), text("trackerUrl"))

private fun JSONObject.error(name: String): EventError =
    optJSONObject(name)?.toError() ?: EventError("Error", null, "", null, null)

private fun JSONObject.toError(): EventError =
    EventError(
        name = text("name"),
        type = textOrNull("type"),
        message = text("message"),
        timestamp = (value("timestamp") as? Number)?.toDouble(),
        cause = optJSONObject("cause")?.toError(),
    )
