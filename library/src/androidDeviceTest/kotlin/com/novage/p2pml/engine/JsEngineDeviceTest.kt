package com.novage.p2pml.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The native library on a device's ABI. The rules are tested on the JVM (JsEngineTest) and in C.
class JsEngineDeviceTest {
    @Test
    fun theEngineRunsAScriptAndATimerWritesToLogcat() {
        val engine = JsEngine()
        val marker = "device test ${System.nanoTime()}"
        try {
            val result = CompletableFuture<String>()
            assertTrue(
                engine.post {
                    runCatching {
                            engine.evaluateScript(
                                "setTimeout(() => console.log('$marker'), 1); 6 * 7",
                                "device.js",
                            )
                        }
                        .fold(result::complete, result::completeExceptionally)
                }
            )
            assertEquals("42", result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(waitForLogcatLine(marker), "No logcat line with \"$marker\"")
        } finally {
            engine.close()
        }
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    // An app reads its own lines from logcat.
    private fun waitForLogcatLine(text: String): Boolean {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < end) {
            val logcat =
                ProcessBuilder("logcat", "-d", "-v", "threadtime", "-s", "P2PML:I")
                    .redirectErrorStream(true)
                    .start()
            val output = logcat.inputStream.bufferedReader().use { it.readText() }
            logcat.waitFor()
            if (output.lines().any { it.contains(" I P2PML") && it.contains(text) }) return true
            Thread.sleep(POLL_MILLISECONDS)
        }
        return false
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MILLISECONDS = 100L
    }
}
