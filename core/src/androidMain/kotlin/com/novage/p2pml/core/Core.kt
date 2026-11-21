package com.novage.p2pml.core

import com.novage.p2pml.engine.JsEngine
import com.novage.p2pml.player.Answer
import com.novage.p2pml.player.CoreSession
import java.io.IOException
import java.io.InterruptedIOException
import org.json.JSONObject

/** A byte range of a segment, as the JS core's `ByteRange`: [start] to [end], inclusive. */
public data class ByteRange(public val start: Long, public val end: Long)

/**
 * The player's playback state, as the JS core's `PlaybackState`: the seconds of media buffered
 * ahead of the playhead, in the buffered range that holds the playhead (0 when the playhead is in a
 * gap), and the playback rate, 0 when the player does not play. Both are finite, 0 or more.
 */
public data class PlaybackState(public val bufferAhead: Double, public val rate: Double)

/** The type of a stream, as the JS core's `StreamType`. */
public enum class StreamType {
    /** The main stream: the video, or the video and the audio together. */
    MAIN,

    /** A secondary stream, such as an audio rendition of its own. */
    SECONDARY,
}

/** A segment that the core loaded, as the JS core's `SegmentResponse`. */
public class SegmentResponse(
    /** The whole segment. */
    public val data: ByteArray,
    /** The bandwidth that the core measured for it, in bits per second. */
    public val bandwidth: Double,
    /**
     * The type of the segment's stream. The core measures [bandwidth] for each stream type in its
     * own way: a player that estimates its bandwidth from every segment counts the main stream
     * only.
     */
    public val streamType: StreamType,
)

/**
 * A load that the core did not finish, as the JS core's `CoreRequestError`: [type] is the JS
 * error's type, such as `"failed"` or `"aborted"`.
 */
public class CoreRequestError(public val type: String, message: String) : IOException(message)

/**
 * The P2P Media Loader core, with the JS core's calls, names and meanings (specs/api.md). A player
 * integration gives it every manifest and loads its segments through it. Any thread; the calls
 * block their thread for the answer, where JS returns a Promise, so call them off the main thread.
 *
 * The core runs in a JS engine with a thread of its own: [close] ends it. Without the library's
 * native code (on 32-bit x86, see KNOWN-ISSUES.md), there is no core: no segment is loadable, and
 * the player loads every request itself.
 */
public class Core internal constructor(config: CoreConfig, newEngine: () -> JsEngine) :
    AutoCloseable {
    @JvmOverloads
    public constructor(
        config: CoreConfig = CoreConfig()
    ) : this(config, { JsEngine(libraryUserAgent()) })

    private val config = config.toJson().toString()
    private val engine: JsEngine? =
        try {
            newEngine()
        } catch (_: LinkageError) {
            null
        }
    // With no engine, a session that takes no call: each call fails at once, as after a close.
    private val session: CoreSession =
        engine?.player ?: CoreSession { _, _, _, notTaken -> notTaken() }

    init {
        session.send("start", listOf(this.config))
    }

    /**
     * The settings that the core uses: the given ones and the core's defaults, as the JS core's
     * `getConfig()`. With no core, and after [close], the given settings, in the same shape.
     *
     * @throws InterruptedIOException when the waiting thread is interrupted.
     */
    @Throws(InterruptedIOException::class)
    public fun getConfig(): CoreConfig {
        val json = session.query("config")?.takeIf { it != "null" }
        return CoreConfig().readFrom(json?.let(::JSONObject) ?: JSONObject(config).inConfigShape())
    }

    /**
     * Gives the core a manifest: [url] after redirects, [requestedUrl] as the player asked for it
     * when a redirect changed it, and its text. From Java, [requestedUrl] is null when there was no
     * redirect: the call has no Java overload, which would be (url, data) and read as (url,
     * requestedUrl).
     */
    public fun processManifest(url: String, requestedUrl: String? = null, data: String) {
        session.send("manifest", listOf(url, requestedUrl, data))
    }

    /**
     * Whether the core loads segment [url], with its [byteRange] if it has one.
     *
     * @throws InterruptedIOException when the waiting thread is interrupted.
     */
    @JvmOverloads
    @Throws(InterruptedIOException::class)
    public fun isSegmentLoadable(url: String, byteRange: ByteRange? = null): Boolean =
        session.query("loadable", listOf(url, byteRange?.text())) == "true"

    /**
     * Loads segment [url], with its [byteRange] if it has one, through the core. Ask
     * [isSegmentLoadable] first. The core keeps one load for each stream type: a new load of the
     * same type aborts the open one.
     *
     * @throws CoreRequestError when the load fails or is aborted.
     * @throws InterruptedIOException when the thread is interrupted: the load is aborted, and the
     *   interrupt stays set.
     */
    @JvmOverloads
    @Throws(IOException::class)
    public fun loadSegment(url: String, byteRange: ByteRange? = null): SegmentResponse {
        val call = session.call("load", listOf(url, byteRange?.text()))
        val answer =
            try {
                call.await()
            } catch (interrupted: InterruptedIOException) {
                abortSegmentLoading(url, byteRange)
                throw interrupted
            }
        return when (answer) {
            is Answer.Segment ->
                SegmentResponse(
                    answer.bytes,
                    answer.bandwidth,
                    if (answer.main) StreamType.MAIN else StreamType.SECONDARY,
                )
            is Answer.Failed -> throw CoreRequestError(answer.type, answer.message)
            // The bridge answers a load with a segment or a failure only.
            is Answer.Value -> error("A load was answered with a value")
        }
    }

    /** Aborts the load of segment [url], with its [byteRange] if it has one. */
    @JvmOverloads
    public fun abortSegmentLoading(url: String, byteRange: ByteRange? = null) {
        session.send("abort", listOf(url, byteRange?.text()))
    }

    /**
     * Gives the core the player's playback state, as the JS core's `updatePlayback`: on each
     * change, and when the player buffers more. With no report, the core takes an empty buffer, and
     * every request is urgent (specs/api.md).
     */
    public fun updatePlayback(state: PlaybackState) {
        session.send("playback", listOf(state.bufferAhead.toString(), state.rate.toString()))
    }

    /**
     * Readies the core for the next source, as the JS core's `destroy()`: its streams, segments and
     * peers go, and the open loads are aborted.
     */
    public fun destroy() {
        session.send("destroy")
    }

    /** Ends the core's engine: the open calls fail, and the core does nothing after it. */
    override fun close() {
        engine?.close()
    }

    private fun ByteRange.text() = "$start-$end"
}
