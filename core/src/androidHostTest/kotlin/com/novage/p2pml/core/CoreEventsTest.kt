package com.novage.p2pml.core

import kotlin.test.Test
import kotlin.test.assertEquals

// Each event's details from the JSON that the bridge gives (native/src/polyfills/player.js): the
// peer, tracker and upload events have no peers in the other host tests.
class CoreEventsTest {
    @Test
    fun streamEvents() {
        assertEquals(
            StreamAddedDetails(STREAM),
            CoreEvent.OnStreamAdded.detailsOf("""{"stream":$STREAM_JSON}"""),
        )
        assertEquals(
            StreamRegistrationErrorDetails(
                "https://a/b.m3u8",
                StreamType.SECONDARY,
                StreamProperties(null, null, null, null, "en", "2", null, null, null),
                EventError("Error", null, "a builder failed", null, null),
            ),
            CoreEvent.OnStreamRegistrationError.detailsOf(
                """
                |{"runtimeId":"https://a/b.m3u8","streamType":"secondary",
                |"properties":{"language":"en","channels":2},
                |"error":{"name":"Error","message":"a builder failed"}}
                """
                    .trimMargin()
            ),
        )
        assertEquals(
            SegmentRegistryMissDetails("https://a/s.ts", ByteRange(0, RANGE_END)),
            CoreEvent.OnSegmentRegistryMiss.detailsOf(
                """{"url":"https://a/s.ts","byteRange":{"start":0,"end":$RANGE_END}}"""
            ),
        )
    }

    @Test
    fun segmentEvents() {
        assertEquals(
            SegmentStartDetails(SEGMENT, DownloadSource.P2P, "peer1", "hash", StreamType.MAIN),
            CoreEvent.OnSegmentStart.detailsOf("""{$SEGMENT_EVENT_JSON}"""),
        )
        assertEquals(
            SegmentLoadDetails(
                SEGMENT,
                DownloadSource.P2P,
                "peer1",
                "hash",
                StreamType.MAIN,
                BYTES,
            ),
            CoreEvent.OnSegmentLoaded.detailsOf("""{$SEGMENT_EVENT_JSON,"bytesLength":$BYTES}"""),
        )
        assertEquals(
            SegmentErrorDetails(
                SEGMENT,
                DownloadSource.P2P,
                "peer1",
                "hash",
                StreamType.MAIN,
                EventError(
                    "RequestError",
                    "peer-closed",
                    "",
                    TIMESTAMP,
                    EventError("PeerError", "connection-lost", "gone", null, null),
                ),
            ),
            CoreEvent.OnSegmentError.detailsOf(
                """{$SEGMENT_EVENT_JSON,"error":{"name":"RequestError","type":"peer-closed",
                |"message":"","timestamp":$TIMESTAMP,"cause":{"name":"PeerError",
                |"type":"connection-lost","message":"gone"}}}"""
                    .trimMargin()
            ),
        )
        // An abort before the download started: no source, no peer.
        assertEquals(
            SegmentAbortDetails(SEGMENT, null, null, "hash", StreamType.MAIN),
            CoreEvent.OnSegmentAbort.detailsOf(
                """{"segment":$SEGMENT_JSON,"infoHash":"hash","streamType":"main"}"""
            ),
        )
    }

    @Test
    fun peerEvents() {
        val peer = PeerDetails("peer1", "hash", StreamType.MAIN, "wss://t")
        val error = EventError("PeerError", "timeout", "late", null, null)
        val errorJson = """{"name":"PeerError","type":"timeout","message":"late"}"""
        assertEquals(peer, CoreEvent.OnPeerConnect.detailsOf("{$PEER_JSON}"))
        assertEquals(peer, CoreEvent.OnPeerClose.detailsOf("{$PEER_JSON}"))
        assertEquals(
            PeerConnectErrorDetails("peer1", "hash", StreamType.MAIN, "wss://t", error),
            CoreEvent.OnPeerConnectError.detailsOf("""{$PEER_JSON,"error":$errorJson}"""),
        )
        assertEquals(
            PeerErrorDetails("peer1", "hash", StreamType.MAIN, "wss://t", error),
            CoreEvent.OnPeerError.detailsOf("""{$PEER_JSON,"error":$errorJson}"""),
        )
        assertEquals(
            PeerWarningDetails("peer1", "hash", StreamType.MAIN, "wss://t", error),
            CoreEvent.OnPeerWarning.detailsOf("""{$PEER_JSON,"warning":$errorJson}"""),
        )
    }

    @Test
    fun chunkAndTrackerEvents() {
        assertEquals(
            ChunkDownloadedDetails(CHUNK, DownloadSource.HTTP, null, StreamType.MAIN, "hash"),
            CoreEvent.OnChunkDownloaded.detailsOf(
                """{"bytesLength":$CHUNK,"downloadSource":"http","streamType":"main",
                |"infoHash":"hash"}"""
                    .trimMargin()
            ),
        )
        assertEquals(
            ChunkUploadedDetails(CHUNK, "peer1", StreamType.SECONDARY, "hash"),
            CoreEvent.OnChunkUploaded.detailsOf(
                """{"bytesLength":$CHUNK,"peerId":"peer1","streamType":"secondary",
                |"infoHash":"hash"}"""
                    .trimMargin()
            ),
        )
        val tracker = """"trackerUrl":"wss://t","infoHash":"hash","streamType":"main""""
        val error = EventError("TrackerError", "announce-failed", "down", null, null)
        val errorJson = """{"name":"TrackerError","type":"announce-failed","message":"down"}"""
        assertEquals(
            TrackerErrorDetails("wss://t", "hash", StreamType.MAIN, error),
            CoreEvent.OnTrackerError.detailsOf("""{$tracker,"error":$errorJson}"""),
        )
        assertEquals(
            TrackerWarningDetails("wss://t", "hash", StreamType.MAIN, error),
            CoreEvent.OnTrackerWarning.detailsOf("""{$tracker,"warning":$errorJson}"""),
        )
    }

    private companion object {
        const val CHUNK = 65_535L
        const val RANGE_END = 99L
        const val BYTES = 3L
        const val TIMESTAMP = 1234.5
        val SEGMENT = Segment("seg-1", 7, "https://a/s.ts", null, 12.0, 16.0)
        const val SEGMENT_JSON =
            """{"runtimeId":"seg-1","externalId":7,"url":"https://a/s.ts",""" +
                """"startTime":12,"endTime":16}"""
        const val SEGMENT_EVENT_JSON =
            """"segment":$SEGMENT_JSON,"downloadSource":"p2p","peerId":"peer1",""" +
                """"infoHash":"hash","streamType":"main""""
        const val PEER_JSON =
            """"peerId":"peer1","infoHash":"hash","streamType":"main","trackerUrl":"wss://t""""
        val STREAM =
            Stream(
                "https://a/v.m3u8",
                StreamType.MAIN,
                StreamProperties(1280000.0, "avc1", 1280, 720, null, null, null, "29.97", null),
                "swarm",
                "main0",
                "swarm-main0",
                "hash",
            )
        const val STREAM_JSON =
            """{"runtimeId":"https://a/v.m3u8","type":"main","properties":{"bitrate":1280000,""" +
                """"codecs":"avc1","width":1280,"height":720,"frameRate":"29.97"},""" +
                """"swarmId":"swarm","streamId":"main0","streamSwarmId":"swarm-main0",""" +
                """"infoHash":"hash"}"""
    }
}
