package com.novage.p2pml.engine

import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsEngineTest {
    private val lines: MutableList<Pair<JsLog.Level, String>> =
        Collections.synchronizedList(mutableListOf())
    private val engine = JsEngine(USER_AGENT) { level, message -> lines += level to message }

    @AfterTest
    fun closeEngine() {
        engine.close()
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    // Runs [block] as a task and returns its result, or throws what it threw.
    private fun <T> onEngine(block: () -> T): T {
        val result = CompletableFuture<T>()
        assertTrue(
            engine.post { runCatching(block).fold(result::complete, result::completeExceptionally) }
        )
        try {
            return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
    }

    @Test
    fun tasksRunInOrderOnOneThread() {
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val order = Collections.synchronizedList(mutableListOf<Int>())
        repeat(TASKS) { i ->
            engine.post {
                threads += Thread.currentThread()
                order += i
            }
        }
        onEngine {}
        assertEquals(List(TASKS) { it }, order)
        assertEquals(1, threads.toSet().size)
        assertTrue(threads.first() !== Thread.currentThread())
    }

    @Test
    fun evaluateScriptGivesTheValue() {
        assertEquals("3", onEngine { engine.evaluateScript("1 + 2", "test.js") })
        // Characters outside the Basic Multilingual Plane too.
        assertEquals("😀 ok", onEngine { engine.evaluateScript("'😀' + ' ok'", "test.js") })
    }

    @Test
    fun aJsExceptionThrowsJsException() {
        val exception =
            assertFailsWith<JsException> {
                onEngine {
                    engine.evaluateScript(
                        "function fail() { throw new RangeError('too far'); }\nfail();",
                        "test.js",
                    )
                }
            }
        assertEquals("RangeError: too far", exception.message)
        assertContains(assertNotNull(exception.jsStack), "at fail (test.js:1")
        assertContains(exception.stackTraceToString(), "at fail (test.js:1")
    }

    @Test
    fun evaluateScriptOffTheEngineThreadThrows() {
        assertFailsWith<IllegalStateException> { engine.evaluateScript("1", "test.js") }
    }

    @Test
    fun promiseJobsRunAfterTheTask() {
        val inTask = onEngine {
            engine.evaluateScript(
                "globalThis.done = false; Promise.resolve().then(() => { done = true; }); done",
                "test.js",
            )
        }
        assertEquals("false", inTask)
        assertEquals("true", onEngine { engine.evaluateScript("done", "test.js") })
    }

    @Test
    fun anExceptionFromATaskIsLoggedAndTheNextTaskRuns() {
        engine.post { engine.evaluateScript("throw new Error('broken task')", "broken.js") }
        assertEquals("2", onEngine { engine.evaluateScript("1 + 1", "test.js") })
        val (level, message) = lines.single()
        assertEquals(JsLog.Level.ERROR, level)
        assertContains(message, "Uncaught exception in an engine task")
        assertContains(message, "Error: broken task")
        // The JS stack too.
        assertContains(message, "broken.js")
    }

    @Test
    fun aScriptAfterTheCloseThrowsJsEngineClosedExceptionThatIsNotLogged() {
        val thrown = CompletableFuture<Throwable?>()
        engine.post {
            engine.close()
            thrown.complete(runCatching { engine.evaluateScript("1", "test.js") }.exceptionOrNull())
            // Uncaught here: not reported.
            engine.evaluateScript("1", "test.js")
        }
        assertIs<JsEngineClosedException>(thrown.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(emptyList(), lines)
    }

    @Test
    fun anErrorFromATaskEndsTheEngine() {
        val ran = Collections.synchronizedList(mutableListOf<Int>())
        // The thread's default handler prints this Error.
        engine.post { throw AssertionError("an expected Error from a test") }
        engine.post { ran += 2 }
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(emptyList(), ran)
        assertFalse(engine.post {})
    }

    @Test
    fun closeFromATaskDropsTheRestAndLaterPosts() {
        val ran = Collections.synchronizedList(mutableListOf<Int>())
        engine.post {
            ran += 1
            engine.close()
            // Not caused by the close: still reported.
            error("after close")
        }
        engine.post { ran += 2 }
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(1), ran)
        assertContains(lines.single().second, "after close")
        // closeEngine() closes it again, which does nothing.
        assertFalse(engine.post {})
    }

    private companion object {
        const val USER_AGENT = "test-agent"
        const val TIMEOUT_SECONDS = 10L
        const val TASKS = 100
    }
}
