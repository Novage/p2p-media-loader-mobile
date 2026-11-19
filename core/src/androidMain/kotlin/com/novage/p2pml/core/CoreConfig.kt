package com.novage.p2pml.core

import kotlin.reflect.KMutableProperty1
import org.json.JSONArray
import org.json.JSONObject

/**
 * The settings of a stream, as the JS core's `StreamConfig` (specs/api.md), with the same names and
 * meanings. Null is unset: the core's default, as an absent JS field. Numbers must be finite, as
 * JSON's. The JS settings that are functions (`httpRequestSetup`, `validateP2PSegment`,
 * `segmentFilter`, `streamIdBuilder`) are not here: a Kotlin function cannot run in the engine.
 */
public open class StreamConfig {
    public var isP2PUploadDisabled: Boolean? = null
    public var isP2PDisabled: Boolean? = null
    public var urgentBufferThreshold: Double? = null
    public var httpDownloadTimeWindow: Double? = null
    public var httpDownloadInitialTimeoutMs: Long? = null
    public var p2pDownloadTimeWindow: Double? = null
    public var simultaneousHttpDownloads: Int? = null
    public var simultaneousP2PDownloads: Int? = null
    public var webRtcMaxMessageSize: Int? = null
    public var p2pNotReceivingBytesTimeoutMs: Long? = null
    public var p2pInactiveLoaderDestroyTimeoutMs: Long? = null
    public var httpNotReceivingBytesTimeoutMs: Long? = null
    public var httpFirstByteTimeoutMs: Long? = null

    /** As in JS, the count of all HTTP tries: 0 makes the core load nothing over HTTP. */
    public var httpErrorRetries: Int? = null
    public var p2pErrorRetries: Int? = null
    public var announceTrackers: List<String>? = null
    public var rtcConfig: RtcConfig? = null
    public var swarmId: String? = null
    public var p2pMaxPeers: Int? = null
    public var p2pChurnMaxPeersMultiplier: Double? = null
    public var p2pChurnCleanupIntervalMs: Long? = null
    public var p2pChurnGracePeriodMs: Long? = null
    public var webRtcOffersCount: Int? = null
    public var webRtcOfferTimeoutMs: Long? = null
    public var webRtcIceGatheringTimeoutMs: Long? = null
    public var webRtcConnectionTimeoutMs: Long? = null
}

/**
 * The settings of the core, as the JS core's `CoreConfig` (specs/api.md): the stream settings for
 * both streams, the common settings, and the settings of each stream type, which override the ones
 * for both.
 */
public class CoreConfig : StreamConfig() {
    /** The memory for segments, in MiB. */
    public var segmentMemoryStorageLimit: Double? = null
    public var trackerClientVersionPrefix: String? = null
    public var mainStream: StreamConfig? = null
    public var secondaryStream: StreamConfig? = null
}

/** The WebRTC settings: the ICE servers, as `RTCConfiguration`'s. */
public class RtcConfig(public val iceServers: List<IceServer>)

/** An ICE server (STUN or TURN), as `RTCIceServer`. */
public class IceServer
@JvmOverloads
constructor(
    public val urls: List<String>,
    public val username: String? = null,
    public val credential: String? = null,
)

// The fields by type, for the JSON of the core's settings.
private val BOOLEANS: List<KMutableProperty1<StreamConfig, Boolean?>> =
    listOf(StreamConfig::isP2PUploadDisabled, StreamConfig::isP2PDisabled)
private val INTS: List<KMutableProperty1<StreamConfig, Int?>> =
    listOf(
        StreamConfig::simultaneousHttpDownloads,
        StreamConfig::simultaneousP2PDownloads,
        StreamConfig::webRtcMaxMessageSize,
        StreamConfig::httpErrorRetries,
        StreamConfig::p2pErrorRetries,
        StreamConfig::p2pMaxPeers,
        StreamConfig::webRtcOffersCount,
    )
private val LONGS: List<KMutableProperty1<StreamConfig, Long?>> =
    listOf(
        StreamConfig::httpDownloadInitialTimeoutMs,
        StreamConfig::p2pNotReceivingBytesTimeoutMs,
        StreamConfig::p2pInactiveLoaderDestroyTimeoutMs,
        StreamConfig::httpNotReceivingBytesTimeoutMs,
        StreamConfig::httpFirstByteTimeoutMs,
        StreamConfig::p2pChurnCleanupIntervalMs,
        StreamConfig::p2pChurnGracePeriodMs,
        StreamConfig::webRtcOfferTimeoutMs,
        StreamConfig::webRtcIceGatheringTimeoutMs,
        StreamConfig::webRtcConnectionTimeoutMs,
    )
private val DOUBLES: List<KMutableProperty1<StreamConfig, Double?>> =
    listOf(
        StreamConfig::urgentBufferThreshold,
        StreamConfig::httpDownloadTimeWindow,
        StreamConfig::p2pDownloadTimeWindow,
        StreamConfig::p2pChurnMaxPeersMultiplier,
    )

/** The set fields as a JSON object, with the JS names. */
internal fun StreamConfig.toJson(): JSONObject {
    val json = JSONObject()
    for (field in BOOLEANS + INTS + LONGS + DOUBLES) {
        json.putOpt(field.name, field.get(this))
    }
    json.putOpt("announceTrackers", announceTrackers?.let(::JSONArray))
    json.putOpt("rtcConfig", rtcConfig?.toJson())
    json.putOpt("swarmId", swarmId)
    if (this is CoreConfig) {
        json.putOpt("segmentMemoryStorageLimit", segmentMemoryStorageLimit)
        json.putOpt("trackerClientVersionPrefix", trackerClientVersionPrefix)
        json.putOpt("mainStream", mainStream?.toJson())
        json.putOpt("secondaryStream", secondaryStream?.toJson())
    }
    return json
}

private fun RtcConfig.toJson() =
    JSONObject()
        .put(
            "iceServers",
            JSONArray(
                iceServers.map { server ->
                    JSONObject()
                        .put("urls", JSONArray(server.urls))
                        .putOpt("username", server.username)
                        .putOpt("credential", server.credential)
                }
            ),
        )

/** Sets the fields that [json] has; a field it does not have stays null. */
internal fun <T : StreamConfig> T.readFrom(json: JSONObject): T {
    for (field in BOOLEANS) field.set(this, json.value(field.name) as? Boolean)
    for (field in INTS) field.set(this, (json.value(field.name) as? Number)?.toInt())
    for (field in LONGS) field.set(this, (json.value(field.name) as? Number)?.toLong())
    for (field in DOUBLES) field.set(this, (json.value(field.name) as? Number)?.toDouble())
    announceTrackers = (json.value("announceTrackers") as? JSONArray)?.strings()
    rtcConfig = (json.value("rtcConfig") as? JSONObject)?.toRtcConfig()
    swarmId = json.value("swarmId") as? String
    if (this is CoreConfig) {
        segmentMemoryStorageLimit = (json.value("segmentMemoryStorageLimit") as? Number)?.toDouble()
        trackerClientVersionPrefix = json.value("trackerClientVersionPrefix") as? String
        mainStream = (json.value("mainStream") as? JSONObject)?.let { StreamConfig().readFrom(it) }
        secondaryStream =
            (json.value("secondaryStream") as? JSONObject)?.let { StreamConfig().readFrom(it) }
    }
    return this
}

// The settings of the core, in the shape of the JS core's `getConfig()`: the common settings, and
// for each stream type its stream settings, its own over the ones for both.
internal fun JSONObject.inConfigShape(): JSONObject {
    val common = setOf("segmentMemoryStorageLimit", "trackerClientVersionPrefix")
    val types = listOf("mainStream", "secondaryStream")
    val shaped = JSONObject()
    val forBoth = JSONObject()
    for (key in keys()) {
        when (key) {
            in common -> shaped.put(key, get(key))
            !in types -> forBoth.put(key, get(key))
        }
    }
    for (type in types) {
        val stream = JSONObject(forBoth.toString())
        optJSONObject(type)?.let { own -> for (key in own.keys()) stream.put(key, own.get(key)) }
        shaped.put(type, stream)
    }
    return shaped
}

// A field's value; null when it is absent or JSON null.
private fun JSONObject.value(name: String): Any? = opt(name)?.takeIf { it != JSONObject.NULL }

private fun JSONArray.strings() = List(length()) { getString(it) }

// `urls` is a string or a list of strings, as in RTCIceServer.
private fun JSONObject.toRtcConfig(): RtcConfig {
    val servers = value("iceServers") as? JSONArray ?: JSONArray()
    return RtcConfig(
        List(servers.length()) { index ->
            val server = servers.getJSONObject(index)
            val urls = server.value("urls")
            IceServer(
                urls = (urls as? JSONArray)?.strings() ?: listOfNotNull(urls as? String),
                username = server.value("username") as? String,
                credential = server.value("credential") as? String,
            )
        }
    )
}
