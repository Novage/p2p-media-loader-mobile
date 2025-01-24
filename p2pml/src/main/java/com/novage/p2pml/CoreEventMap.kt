package com.novage.p2pml

import kotlinx.serialization.Serializable

/**
 * CoreEventMap is a sealed class that represents the different types of events that can be emitted by the P2P core.
 *
 * See [P2P Media Loader CoreEventMap](https://novage.github.io/p2p-media-loader/docs/v2.1.0/types/p2p-media-loader-core.CoreEventMap.html)
 */
sealed class CoreEventMap<T> {
    /**
     * Fired when a segment is fully downloaded and available for use.
     */
    object OnSegmentLoaded : CoreEventMap<SegmentLoadDetails>()

    /**
     * Fired at the beginning of a segment download process.
     */
    object OnSegmentStart : CoreEventMap<SegmentStartDetails>()

    /**
     * Fired when an error occurs during the download of a segment.
     */
    object OnSegmentError : CoreEventMap<SegmentErrorDetails>()

    /**
     * Fired if the download of a segment is aborted before completion.
     */
    object OnSegmentAbort : CoreEventMap<SegmentAbortDetails>()

    /**
     * Fired when a new peer-to-peer connection is established.
     */
    object OnPeerConnect : CoreEventMap<PeerDetails>()

    /**
     * Fired when an existing peer-to-peer connection is closed.
     */
    object OnPeerClose : CoreEventMap<PeerDetails>()

    /**
     * Fired when an error occurs during a peer-to-peer connection.
     */
    object OnPeerError : CoreEventMap<PeerErrorDetails>()

    /**
     * Fired after a chunk of data from a segment has been successfully downloaded.
     */
    object OnChunkDownloaded : CoreEventMap<ChunkDownloadedDetails>()

    /**
     * Fired when a chunk of data has been successfully uploaded to a peer.
     */
    object OnChunkUploaded : CoreEventMap<ChunkUploadedDetails>()

    /**
     * Fired when an error occurs during the tracker request process.
     */
    object OnTrackerError : CoreEventMap<TrackerErrorDetails>()

    /**
     * Fired when a warning occurs during the tracker request process.
     */
    object OnTrackerWarning : CoreEventMap<TrackerWarningDetails>()
}

/**
 * Represents a range of bytes, used for specifying a segment of data to download.
 *
 * @property start The starting byte index of the range.
 * @property end The ending byte index of the range.
 */
@Serializable
data class SegmentByteRange(
    val start: Int,
    val end: Int,
)

/**
 * Represents the details of a peer in a peer-to-peer network.
 *
 * @property peerId The unique identifier for a peer in the network.
 * @property streamType The type of stream that the peer is connected to.
 */
@Serializable
data class PeerDetails(
    val peerId: String,
    val streamType: String,
)

/**
 * Represents the details of a peer error event.
 *
 * @property peerId The unique identifier for a peer in the network.
 * @property streamType The type of stream that the peer is connected to.
 * @property error The error that occurred during the peer-to-peer connection.
 */
@Serializable
data class PeerErrorDetails(
    val peerId: String,
    val streamType: String,
    val error: String,
)

/**
 * Describes a media segment with its unique identifiers, location, and timing information.
 *
 * @property runtimeId The unique identifier of the segment.
 * @property externalId The external identifier of the segment.
 * @property url The URL of the segment.
 * @property byteRange The byte range of the segment.
 * @property startTime The start time of the segment.
 * @property endTime The end time of the segment.
 */
@Serializable
data class SegmentDetails(
    val runtimeId: String,
    val externalId: Int,
    val url: String,
    val byteRange: SegmentByteRange? = null,
    val startTime: Double,
    val endTime: Double,
)

/**
 * Represents the details about a loaded segment.
 *
 * @property segmentUrl The URL of the segment.
 * @property bytesLength The length of the segment in bytes.
 * @property downloadSource The source from which the segment was downloaded.
 * @property peerId The ID of the peer from which the segment was downloaded.
 * @property streamType The type of stream.
 */
@Serializable
data class SegmentLoadDetails(
    val segmentUrl: String,
    val bytesLength: Int,
    val downloadSource: String,
    val peerId: String? = null,
    val streamType: String,
)

/**
 * Represents details about a segment event.
 *
 * @property segment The segment that the event is about.
 * @property downloadSource The origin of the segment download.
 * @property peerId The ID of the peer from which the segment was downloaded.
 *
 */
@Serializable
data class SegmentStartDetails(
    val segment: SegmentDetails,
    val downloadSource: String,
    val peerId: String? = null,
)

/**
 * Represents details about a segment error event.
 *
 *  @property error The error message.
 *  @property segment The segment that the event is about.
 *  @property downloadSource The origin of the segment download.
 *  @property peerId The ID of the peer from which the segment was downloaded.
 *  @property streamType The type of stream.
 *
 */
@Serializable
data class SegmentErrorDetails(
    val error: String,
    val segment: SegmentDetails,
    val downloadSource: String,
    val peerId: String?,
    val streamType: String,
)

/**
 * Represents details about a segment abort event.
 *
 * @property segment The segment that the event is about.
 * @property downloadSource The origin of the segment download.
 * @property peerId The ID of the peer from which the segment was downloaded.
 * @property streamType The type of stream.
 */
@Serializable
data class SegmentAbortDetails(
    val segment: SegmentDetails,
    val downloadSource: String,
    val peerId: String? = null,
    val streamType: String,
)

/**
 * Represents the details of a chunk downloaded event.
 *
 * @property bytesLength The length of the chunk in bytes.
 * @property downloadSource The source from which the chunk was downloaded.
 * @property peerId The ID of the peer from which the chunk was downloaded (if downloaded from a peer).
 */
data class ChunkDownloadedDetails(
    val bytesLength: Int,
    val downloadSource: String,
    val peerId: String?,
)

/**
 * Represents the details of a chunk uploaded event.
 *
 * @property bytesLength The length of the chunk in bytes.
 * @property peerId The ID of the peer to which the chunk was uploaded.
 */
data class ChunkUploadedDetails(
    val bytesLength: Int,
    val peerId: String,
)

/**
 * Represents the details of a tracker error event.
 *
 * @property streamType The type of stream that the tracker is for.
 * @property error The error that occurred during the tracker request.
 */
@Serializable
data class TrackerErrorDetails(
    val streamType: String,
    val error: String,
)

/**
 * Represents the details of a tracker warning event.
 *
 * @property streamType The type of stream that the tracker is for.
 * @property warning The warning that occurred during the tracker request.
 */
@Serializable
data class TrackerWarningDetails(
    val streamType: String,
    val warning: String,
)
