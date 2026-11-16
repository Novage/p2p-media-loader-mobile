package com.novage.p2pml.engine

import java.util.concurrent.TimeUnit

/**
 * A QuickJS engine on a thread of its own, which runs posted tasks and JS timers one at a time.
 * Only the engine thread touches the JS runtime: tasks call [evaluateScript]. Console lines and
 * uncaught JS errors go to [log]. See specs/engine.md.
 */
internal class JsEngine(private val log: JsLog = JsLog.logcat) {
    private val lock = Any()
    // Before the native engine, so that a failure here leaves nothing to free.
    private val thread = Thread(null, ::runEngine, "p2pml-engine", STACK_SIZE)
    // Zero once the engine thread has freed the engine. Set by the constructor, then changes only
    // under [lock]; the engine thread may read it without the lock, because only that thread sets
    // it to zero.
    private var handle: Long = NativeEngine.create()

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
    fun close() {
        synchronized(lock) {
            if (handle != 0L) NativeEngine.close(handle)
        }
    }

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
            if (!NativeEngine.run(handle, sink)) {
                log.write(JsLog.Level.ERROR, "The JS runtime or its globals could not be created")
            }
        } finally {
            synchronized(lock) {
                NativeEngine.free(handle)
                handle = 0
            }
        }
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

    private fun checkEngineThread() {
        check(Thread.currentThread() === thread) { "Not on the engine thread" }
    }

    private companion object {
        // QuickJS stops JS at 1 MiB of stack (its default); the rest is for native frames.
        const val STACK_SIZE = 2L * 1024 * 1024
    }
}
