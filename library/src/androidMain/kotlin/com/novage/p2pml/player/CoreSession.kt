package com.novage.p2pml.player

import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The core's answer to a segment load. */
internal sealed interface SegmentResult {
    /** The core does not load the segment: the player loads it itself. */
    data object Miss : SegmentResult

    /** The whole segment. */
    class Data(val bytes: ByteArray) : SegmentResult

    /** The load failed: the player can try again. */
    data class Failed(val message: String) : SegmentResult
}

/** The engine's side of the player bridge (native/src/polyfills/player.js). */
internal fun interface PlayerPort {
    /**
     * From any thread: gives event [name] of load [id] to the bridge later, on the engine thread,
     * with its [texts]; null is none. [notTaken] runs when JS did not take it: there, or at once on
     * this thread when the engine is closed.
     */
    fun send(name: String, id: Int, texts: List<String?>, notTaken: () -> Unit)
}

/**
 * The core of one player, in an engine: the player gives it its manifests and loads its segments
 * through it (specs/engine.md, "The player bridge"). Any thread. Each load gets one
 * [SegmentResult]; when the engine closes, every open load is a [SegmentResult.Miss], so that the
 * player loads the segment itself and never waits for good.
 */
internal class CoreSession(private val port: PlayerPort) {
    private val lastId = AtomicInteger()
    // The loads that have no answer yet.
    private val loads = ConcurrentHashMap<Int, CompletableFuture<SegmentResult>>()

    /**
     * Makes the core, with [config] as its settings: a JSON object. A second start needs a [stop]
     * before it: else it is reported, and the core keeps its settings.
     */
    fun start(config: String) {
        port.send("start", 0, listOf(config)) {}
    }

    /**
     * Gives the core a manifest: [url] after redirects, [requestedUrl] as the player asked for it
     * when a redirect changed it, and its [text].
     */
    fun processManifest(url: String, requestedUrl: String?, text: String) {
        port.send("manifest", 0, listOf(url, requestedUrl, text)) {}
    }

    /**
     * Loads segment [url], with its byte [range] (inclusive) if it has one, through the core. The
     * core keeps one load for each stream type: a new load of the same type fails the open one with
     * "aborted".
     */
    fun load(url: String, range: LongRange?): SegmentLoad {
        val id = lastId.incrementAndGet()
        val load = SegmentLoad(id)
        loads[id] = load.result
        val texts = listOf(url, range?.let { "${it.first}-${it.last}" })
        port.send("load", id, texts) { answer(id, SegmentResult.Miss) }
        return load
    }

    /** Destroys the core: each open load fails. [start] can make it again. */
    fun stop() {
        port.send("stop", 0, emptyList()) {}
    }

    /** The bridge's answer to load [id], on the engine thread. */
    fun answer(id: Int, result: SegmentResult) {
        loads.remove(id)?.complete(result)
    }

    /** After the engine closed: every open load is a miss. */
    fun engineClosed() {
        for (id in loads.keys) {
            answer(id, SegmentResult.Miss)
        }
    }

    /** A load of a segment through the core. */
    inner class SegmentLoad internal constructor(private val id: Int) {
        internal val result = CompletableFuture<SegmentResult>()

        /**
         * Waits for the core's answer. An interrupt of the waiting thread aborts the load.
         *
         * @throws InterruptedIOException when the thread is interrupted; its interrupt stays set.
         */
        fun await(): SegmentResult =
            try {
                result.get()
            } catch (_: InterruptedException) {
                cancel()
                Thread.currentThread().interrupt()
                throw InterruptedIOException("The load of a segment was interrupted")
            }

        /** Aborts the load, unless it has its answer. Any thread. */
        fun cancel() {
            if (loads.remove(id, result)) {
                result.complete(SegmentResult.Failed("aborted"))
                port.send("abort", id, emptyList()) {}
            }
        }
    }
}
