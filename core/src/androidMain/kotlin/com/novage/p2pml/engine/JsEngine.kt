package com.novage.p2pml.engine

import com.novage.p2pml.http.HttpBridge
import com.novage.p2pml.http.HttpEvents
import com.novage.p2pml.http.SharedHttpClient
import com.novage.p2pml.http.WsBridge
import com.novage.p2pml.http.WsEvents
import com.novage.p2pml.player.Answer
import com.novage.p2pml.player.CoreSession
import java.util.concurrent.TimeUnit

/**
 * A QuickJS engine on a thread of its own, which runs posted tasks and JS timers one at a time.
 * Only the engine thread touches the JS runtime: tasks call [evaluateScript]. JS gets [userAgent]
 * as `navigator.userAgent`, such as [com.novage.p2pml.core.libraryUserAgent], and `fetch` and
 * `WebSocket` over [SharedHttpClient]. Console lines and uncaught JS errors go to [log]. See
 * specs/engine.md.
 */
internal class JsEngine(userAgent: String, internal val log: JsLog = JsLog.logcat) {
    private val lock = Any()
    // The requests of fetch. Before the thread starts: the engine thread uses it.
    private val http =
        HttpBridge({ SharedHttpClient.client }, Delivery(), onEngine = { block -> post(block) })
    // The sockets of WebSocket, as [http].
    private val ws =
        WsBridge(
            { SharedHttpClient.client },
            userAgent,
            WsDelivery(),
            onEngine = { block -> post(block) },
        )
    // Before the native engine, so that a failure here leaves nothing to free.
    private val thread = Thread(null, ::runEngine, "p2pml-engine", STACK_SIZE)
    // Zero once the engine thread has freed the engine. Set by the constructor, then changes only
    // under [lock]; the engine thread may read it without the lock, because only that thread sets
    // it to zero.
    private var handle: Long = NativeEngine.create(userAgent.encodeToByteArray())

    /** The player bridge: the core of this engine's player. */
    val player =
        CoreSession(
            port = { name, id, texts, notTaken ->
                // Encoded on the caller's thread, not the engine's.
                val bytes = Array(texts.size) { texts[it]?.encodeToByteArray() }
                val posted = post {
                    if (!NativeEngine.playerEvent(handle, name, id, bytes)) {
                        notTaken()
                    }
                }
                if (!posted) {
                    notTaken()
                }
            },
            onEngineThread = ::isEngineThread,
        )

    init {
        // Not inherited from the thread that creates the engine.
        thread.priority = Thread.NORM_PRIORITY
        // An engine left open does not keep a JVM, such as the tests', alive.
        thread.isDaemon = true
        try {
            thread.start()
        } catch (error: OutOfMemoryError) {
            // No thread to free it: the engine never ran.
            NativeEngine.free(handle)
            handle = 0
            throw error
        }
    }

    /**
     * Runs [task] on the engine thread after the tasks before it. False once closed, or when out of
     * memory.
     */
    fun post(task: () -> Unit): Boolean =
        synchronized(lock) { handle != 0L && NativeEngine.post(handle, Runnable { runTask(task) }) }

    /**
     * Evaluates [code] as a script in the global scope and returns its completion value, converted
     * to a string as `${'$'}{value}` does. Engine thread only. Promise jobs it queues run after the
     * current task.
     *
     * @throws JsException for a JS exception.
     * @throws JsEngineClosedException once the engine is closed, also when the script completed.
     */
    fun evaluateScript(code: String, fileName: String): String {
        checkEngineThread()
        return NativeEngine.evaluateScript(handle, code.encodeToByteArray(), fileName)
    }

    /**
     * Closes the engine from any thread, also from a task, without waiting: JS that still runs
     * stops with an uncatchable error, the current task finishes, then no JS runs, the thread frees
     * the runtime and the pending timers, drops the pending tasks, and ends. Closing again does
     * nothing.
     */
    fun close() = withHandle(Unit) { NativeEngine.close(it) }

    /** Waits until the engine thread has freed the engine and ended. For tests. */
    internal fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean {
        unit.timedJoin(thread, timeout)
        return !thread.isAlive
    }

    private fun runEngine() {
        try {
            val sink = NativeEngine.LogSink { level, message ->
                log.write(JsLog.Level.entries[level], message)
            }
            val httpSink =
                object : NativeEngine.HttpSink {
                    override fun start(
                        id: Int,
                        method: String,
                        url: String,
                        headers: Array<String>,
                    ) = http.start(id, method, url, headers.asList())

                    override fun cancel(id: Int) = http.cancel(id)
                }
            val wsSink =
                object : NativeEngine.WsSink {
                    override fun open(id: Int, url: String, protocols: Array<String>) =
                        ws.open(id, url, protocols.asList())

                    override fun send(id: Int, binary: Boolean, bytes: ByteArray) =
                        ws.send(id, binary, bytes)

                    override fun close(id: Int, code: Int, reason: ByteArray) =
                        ws.close(id, code, reason)

                    override fun cancel(id: Int) = ws.cancel(id)
                }
            val playerSink =
                object : NativeEngine.PlayerSink {
                    override fun value(id: Int, json: ByteArray) =
                        player.answer(id, Answer.Value(json.decodeToString()))

                    override fun segment(
                        id: Int,
                        bytes: ByteArray,
                        bandwidth: Double,
                        main: Boolean,
                    ) = player.answer(id, Answer.Segment(bytes, bandwidth, main))

                    override fun fail(id: Int, type: ByteArray, message: ByteArray) =
                        player.answer(
                            id,
                            Answer.Failed(type.decodeToString(), message.decodeToString()),
                        )

                    override fun coreEvent(name: ByteArray, json: ByteArray) =
                        player.onEvent(name.decodeToString(), json.decodeToString())

                    override fun call(function: Int, json: ByteArray) =
                        player.onCall(function, json.decodeToString())?.encodeToByteArray()
                }
            if (!NativeEngine.run(handle, sink, httpSink, wsSink, playerSink)) {
                log.write(JsLog.Level.ERROR, "The JS runtime or its globals could not be created")
            }
        } finally {
            player.engineClosed()
            http.close()
            ws.close()
            synchronized(lock) {
                NativeEngine.free(handle)
                handle = 0
            }
        }
    }

    // Under [lock] and only while [handle] is not 0, so that a call from another thread never
    // reaches a freed engine.
    private inline fun <T> withHandle(otherwise: T, call: (Long) -> T): T =
        synchronized(lock) { if (handle != 0L) call(handle) else otherwise }

    /** Gives the events of fetch's requests to the native engine, from Ktor's threads. */
    private inner class Delivery : HttpEvents {
        override fun response(
            id: Int,
            status: Int,
            statusText: String,
            url: String,
            headers: List<String>,
        ) =
            withHandle(false) {
                NativeEngine.httpResponse(it, id, status, statusText, url, headers.toTypedArray())
            }

        override fun data(id: Int, bytes: ByteArray, length: Int) =
            withHandle(false) { NativeEngine.httpData(it, id, bytes, length) }

        override fun end(id: Int) = withHandle(false) { NativeEngine.httpEnd(it, id) }

        override fun fail(id: Int, message: String) =
            withHandle(false) { NativeEngine.httpFail(it, id, message) }
    }

    /** Gives the events of WebSocket's sockets to the native engine, from Ktor's threads. */
    private inner class WsDelivery : WsEvents {
        override fun open(id: Int, protocol: String, extensions: String) =
            withHandle(false) { NativeEngine.wsOpen(it, id, protocol, extensions) }

        override fun message(id: Int, binary: Boolean, bytes: ByteArray) =
            withHandle(false) { NativeEngine.wsMessage(it, id, binary, bytes) }

        override fun close(
            id: Int,
            code: Int,
            reason: ByteArray,
            wasClean: Boolean,
            failed: Boolean,
        ) = withHandle(false) { NativeEngine.wsClose(it, id, code, reason, wasClean, failed) }
    }

    // An exception from a task is a defect of the library: report it and let the next task run,
    // as a browser does with an exception from an event handler. An Error ends the thread.
    @Suppress("TooGenericExceptionCaught")
    private fun runTask(task: () -> Unit) {
        try {
            task()
        } catch (_: JsEngineClosedException) {
            // The engine closed while the task ran JS: not a defect.
        } catch (exception: Exception) {
            log.write(
                JsLog.Level.ERROR,
                "Uncaught exception in an engine task: ${exception.stackTraceToString()}",
            )
        }
    }

    private fun isEngineThread() = Thread.currentThread() === thread

    private fun checkEngineThread() {
        check(isEngineThread()) { "Not on the engine thread" }
    }

    private companion object {
        // QuickJS stops JS at 1 MiB of stack (its default); the rest is for native frames.
        const val STACK_SIZE = 2L * 1024 * 1024
    }
}
