package com.novage.p2pml.core

import kotlin.reflect.KMutableProperty1
import org.json.JSONArray
import org.json.JSONObject

/**
 * The settings of a stream that the core can change while it runs, as the JS core's
 * `DynamicStreamConfig` (specs/api.md), with the same names and meanings. Null is unset: the core's
 * default, or with [Core.applyDynamicConfig] the value that the core has now. Numbers must be
 * finite, as JSON's. The async JS function settings (`httpRequestSetup`, `validateP2PSegment`) are
 * not in the API (KNOWN-ISSUES.md).
 */
public open class DynamicStreamConfig {
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
    public var rtcConfig: RtcConfig? = null
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
 * The settings of a stream, as the JS core's `StreamConfig` (specs/api.md): the dynamic ones, and
 * the ones that are not dynamic, as in JS: [announceTrackers], [swarmId] (or [swarmIdFunction]),
 * [streamIdBuilder] and [segmentFilter]. The functions run on the core's thread (specs/api.md,
 * "Functions in the settings").
 */
public open class StreamConfig : DynamicStreamConfig() {
    public var announceTrackers: List<String>? = null
    public var swarmId: String? = null

    /**
     * The JS core's `swarmId` as a function: a swarm for each source. Set it or [swarmId], not both
     * in one config: [Core]'s constructor refuses that.
     */
    public var swarmIdFunction: SwarmIdFunction? = null
    public var streamIdBuilder: StreamIdBuilder? = null
    public var segmentFilter: SegmentFilter? = null
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

/**
 * The changes of the core's settings while it runs, as the JS core's `DynamicCoreConfig`
 * (specs/api.md): for [Core.applyDynamicConfig]. Only the set (not null) fields change: the others
 * keep their values. Of a config given as [mainStream] or [secondaryStream], only the stream
 * settings apply: a [StreamConfig] goes without its `announceTrackers`, `swarmId` and functions,
 * which do not change. As in JS, [segmentMemoryStorageLimit] applies from the next segment storage
 * that the core makes (at the first load, and at the first after [Core.destroy]), and
 * [trackerClientVersionPrefix] does not apply.
 */
public class DynamicCoreConfig : DynamicStreamConfig() {
    /** The memory for segments, in MiB. */
    public var segmentMemoryStorageLimit: Double? = null
    public var trackerClientVersionPrefix: String? = null
    public var mainStream: DynamicStreamConfig? = null
    public var secondaryStream: DynamicStreamConfig? = null
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
private val BOOLEANS: List<KMutableProperty1<DynamicStreamConfig, Boolean?>> =
    listOf(DynamicStreamConfig::isP2PUploadDisabled, DynamicStreamConfig::isP2PDisabled)
private val INTS: List<KMutableProperty1<DynamicStreamConfig, Int?>> =
    listOf(
        DynamicStreamConfig::simultaneousHttpDownloads,
        DynamicStreamConfig::simultaneousP2PDownloads,
        DynamicStreamConfig::webRtcMaxMessageSize,
        DynamicStreamConfig::httpErrorRetries,
        DynamicStreamConfig::p2pErrorRetries,
        DynamicStreamConfig::p2pMaxPeers,
        DynamicStreamConfig::webRtcOffersCount,
    )
private val LONGS: List<KMutableProperty1<DynamicStreamConfig, Long?>> =
    listOf(
        DynamicStreamConfig::httpDownloadInitialTimeoutMs,
        DynamicStreamConfig::p2pNotReceivingBytesTimeoutMs,
        DynamicStreamConfig::p2pInactiveLoaderDestroyTimeoutMs,
        DynamicStreamConfig::httpNotReceivingBytesTimeoutMs,
        DynamicStreamConfig::httpFirstByteTimeoutMs,
        DynamicStreamConfig::p2pChurnCleanupIntervalMs,
        DynamicStreamConfig::p2pChurnGracePeriodMs,
        DynamicStreamConfig::webRtcOfferTimeoutMs,
        DynamicStreamConfig::webRtcIceGatheringTimeoutMs,
        DynamicStreamConfig::webRtcConnectionTimeoutMs,
    )
private val DOUBLES: List<KMutableProperty1<DynamicStreamConfig, Double?>> =
    listOf(
        DynamicStreamConfig::urgentBufferThreshold,
        DynamicStreamConfig::httpDownloadTimeWindow,
        DynamicStreamConfig::p2pDownloadTimeWindow,
        DynamicStreamConfig::p2pChurnMaxPeersMultiplier,
    )

/**
 * The set fields as a JSON object, with the JS names. With [functions], also the functions, as its
 * markers; without, none.
 *
 * @throws IllegalArgumentException with [functions], when a config has both [StreamConfig.swarmId]
 *   and [StreamConfig.swarmIdFunction].
 */
internal fun DynamicStreamConfig.toJson(functions: FunctionTable? = null): JSONObject {
    val json = JSONObject()
    for (field in BOOLEANS + INTS + LONGS + DOUBLES) {
        json.putOpt(field.name, field.get(this))
    }
    json.putOpt("rtcConfig", rtcConfig?.toJson())
    if (this is StreamConfig) {
        json.putOpt("announceTrackers", announceTrackers?.let(::JSONArray))
        json.putOpt("swarmId", swarmId)
        if (functions != null) {
            require(swarmId == null || swarmIdFunction == null) {
                "A config has both swarmId and swarmIdFunction"
            }
            json.putOpt("swarmId", swarmIdFunction?.let { functions.marker(it) })
            json.putOpt("streamIdBuilder", streamIdBuilder?.let { functions.marker(it) })
            json.putOpt("segmentFilter", segmentFilter?.let { functions.marker(it) })
        }
    }
    // The common settings, and the settings of each stream type.
    when (this) {
        is CoreConfig -> {
            json.putOpt("segmentMemoryStorageLimit", segmentMemoryStorageLimit)
            json.putOpt("trackerClientVersionPrefix", trackerClientVersionPrefix)
            json.putOpt("mainStream", mainStream?.toJson(functions))
            json.putOpt("secondaryStream", secondaryStream?.toJson(functions))
        }
        is DynamicCoreConfig -> {
            json.putOpt("segmentMemoryStorageLimit", segmentMemoryStorageLimit)
            json.putOpt("trackerClientVersionPrefix", trackerClientVersionPrefix)
            json.putOpt("mainStream", mainStream?.dynamicJson())
            json.putOpt("secondaryStream", secondaryStream?.dynamicJson())
        }
    }
    return json
}

// Without the settings that are not dynamic, which a StreamConfig in its place has: JS does not
// ignore announceTrackers in a change.
private fun DynamicStreamConfig.dynamicJson() =
    toJson().apply {
        remove("announceTrackers")
        remove("swarmId")
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

/**
 * Sets the fields that [json] has; a field it does not have stays null. A function is its marker of
 * [functions].
 */
internal fun <T : StreamConfig> T.readFrom(json: JSONObject, functions: FunctionTable): T {
    for (field in BOOLEANS) field.set(this, json.value(field.name) as? Boolean)
    for (field in INTS) field.set(this, (json.value(field.name) as? Number)?.toInt())
    for (field in LONGS) field.set(this, (json.value(field.name) as? Number)?.toLong())
    for (field in DOUBLES) field.set(this, (json.value(field.name) as? Number)?.toDouble())
    announceTrackers = (json.value("announceTrackers") as? JSONArray)?.strings()
    rtcConfig = (json.value("rtcConfig") as? JSONObject)?.toRtcConfig()
    swarmId = json.value("swarmId") as? String
    swarmIdFunction = functions.functionOf(json.value("swarmId")) as? SwarmIdFunction
    streamIdBuilder = functions.functionOf(json.value("streamIdBuilder")) as? StreamIdBuilder
    segmentFilter = functions.functionOf(json.value("segmentFilter")) as? SegmentFilter
    if (this is CoreConfig) {
        segmentMemoryStorageLimit = (json.value("segmentMemoryStorageLimit") as? Number)?.toDouble()
        trackerClientVersionPrefix = json.value("trackerClientVersionPrefix") as? String
        mainStream =
            (json.value("mainStream") as? JSONObject)?.let {
                StreamConfig().readFrom(it, functions)
            }
        secondaryStream =
            (json.value("secondaryStream") as? JSONObject)?.let {
                StreamConfig().readFrom(it, functions)
            }
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
internal fun JSONObject.value(name: String): Any? = opt(name)?.takeIf { it != JSONObject.NULL }

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
