package com.novage.p2pml.engine

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The globals through JNI: their rules are tested in native/test/engine_test.c.
class JsGlobalsTest {
    private val lines = LinkedBlockingQueue<Pair<JsLog.Level, String>>()
    private val engine = JsEngine { level, message -> lines.put(level to message) }

    @AfterTest
    fun closeEngine() {
        engine.close()
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    private fun evaluate(code: String) {
        assertTrue(engine.post { engine.evaluateScript(code, "test.js") })
    }

    private fun nextLine() = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    @Test
    fun consoleWritesEachLineAtItsLevel() {
        evaluate(
            "console.debug('d'); console.log('l'); console.info('i'); console.warn('w');" +
                "console.error('e', '\\u{1F600}');"
        )
        val expected =
            listOf(
                JsLog.Level.DEBUG to "d",
                JsLog.Level.INFO to "l",
                JsLog.Level.INFO to "i",
                JsLog.Level.WARN to "w",
                JsLog.Level.ERROR to "e 😀",
            )
        assertEquals(expected, List(expected.size) { nextLine() })
    }

    @Test
    fun anExceptionFromATimerIsReported() {
        evaluate("setTimeout(function late() { throw new Error('in a timer'); }, 10)")
        val (level, message) = nextLine()
        assertEquals(JsLog.Level.ERROR, level)
        assertContains(message, "Uncaught Error: in a timer\n    at late (test.js:1")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
