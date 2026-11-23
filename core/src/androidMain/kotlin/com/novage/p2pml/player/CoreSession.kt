package com.novage.p2pml.player

import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The bridge's answer to a call. */
internal sealed interface Answer {
    /** The answer of a query, as JSON. */
    class Value(val json: String) : Answer

    /**
     * The whole segment of a load, the bandwidth (bits per second) the core measured, and whether
     * its stream is of the main type.
     */
    class Segment(val bytes: ByteArray, val bandwidth: Double, val main: Boolean) : Answer

    /** A failed load: the error's type, such as "failed" or "aborted", and its message. */
    class Failed(val type: String, val message: String) : Answer
}

/** The engine's side of the player bridge (native/src/polyfills/player.js). */
internal fun interface PlayerPort {
    /**
     * From any thread: gives event [name] of call [id] to the bridge later, on the engine thread,
     * with its [texts]; null is none. [notTaken] runs when JS did not take it: there, or at once on
     * this thread when the engine is closed.
     */
    fun send(name: String, id: Int, texts: List<String?>, notTaken: () -> Unit)
}

/**
 * The core in an engine, as the bridge gives its calls (specs/engine.md, "The player bridge"). Any
 * thread but the engine's for a call that has an answer ([onEngineThread]): there it would wait for
 * itself. A query and a load get one [Answer] each, which the caller waits for; a call that JS does
 * not take, an open call when the engine closes, and a call after it fail, so that no caller waits
 * for good.
 */
internal class CoreSession(
    private val port: PlayerPort,
    private val onEngineThread: () -> Boolean = { false },
) {
    private val lastId = AtomicInteger()
    // The calls that have no answer yet.
    private val calls = ConcurrentHashMap<Int, CompletableFuture<Answer>>()

    /** An event with no answer. */
    fun send(name: String, texts: List<String?> = emptyList()) {
        port.send(name, 0, texts) {}
    }

    /**
     * Query [name] (such as `loadable` or `config`): its JSON, or null when it is JSON null or it
     * failed (not taken, or the engine closed).
     *
     * @throws InterruptedIOException when the waiting thread is interrupted.
     */
    fun query(name: String, texts: List<String?> = emptyList()): String? =
        (call(name, texts).await() as? Answer.Value)?.json?.takeIf { it != "null" }

    /**
     * Starts a call that has an answer; [Call.await] waits for it.
     *
     * @throws IllegalStateException on the engine thread, such as from a function of the core's
     *   settings.
     */
    fun call(name: String, texts: List<String?>): Call {
        check(!onEngineThread()) {
            "A call to the core cannot wait on the core's thread, such as in a function of its settings"
        }
        val id = lastId.incrementAndGet()
        val call = Call(id)
        calls[id] = call.answer
        port.send(name, id, texts) { answer(id, closedFailure()) }
        return call
    }

    /** Takes the core's event [name] with its JSON details, on the engine thread. */
    @Volatile var onEvent: (name: String, json: String) -> Unit = { _, _ -> }

    /**
     * Calls the platform's [function] with its argument as JSON, on the engine thread: the answer
     * as JSON, or null for none.
     */
    @Volatile var onCall: (function: Int, json: String) -> String? = { _, _ -> null }

    /** The bridge's answer to call [id], on the engine thread. */
    fun answer(id: Int, answer: Answer) {
        calls.remove(id)?.complete(answer)
    }

    /** After the engine closed: every open call fails. */
    fun engineClosed() {
        for (id in calls.keys) {
            answer(id, closedFailure())
        }
    }

    private fun closedFailure() = Answer.Failed("failed", "the engine did not take the call")

    /** A call that has an answer. */
    inner class Call internal constructor(private val id: Int) {
        internal val answer = CompletableFuture<Answer>()

        /**
         * Waits for the answer.
         *
         * @throws InterruptedIOException when the thread is interrupted; its interrupt stays set,
         *   and the call has no answer then.
         */
        fun await(): Answer =
            try {
                answer.get()
            } catch (_: InterruptedException) {
                calls.remove(id, answer)
                Thread.currentThread().interrupt()
                throw InterruptedIOException("The wait for the core was interrupted")
            }
    }
}
