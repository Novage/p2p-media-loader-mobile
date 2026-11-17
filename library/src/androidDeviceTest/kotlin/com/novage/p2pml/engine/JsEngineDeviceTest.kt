package com.novage.p2pml.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The native library on a device's ABI. The rules are tested on the JVM (JsEngineTest) and in C.
class JsEngineDeviceTest {
    @Test
    fun theEngineRunsAScriptAndATimerWritesToLogcat() {
        val engine = JsEngine(libraryUserAgent())
        val marker = "device test ${System.nanoTime()}"
        try {
            val result = CompletableFuture<String>()
            assertTrue(
                engine.post {
                    runCatching {
                            engine.evaluateScript(
                                "setTimeout(() => console.log('$marker'), 1);" +
                                    // The polyfills and Ada of the Android build, with an
                                    // IDNA host and a query; a number result.
                                    "const u = new URL('../?n=6', 'https://b\\u00fccher.example/a/b');" +
                                    "(u.host === 'xn--bcher-kva.example' ? new TextDecoder().decode(" +
                                    "new TextEncoder().encode(u.searchParams.get('n'))) : 0) * 7",
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

    // The core's bundle in the Android build. Its load time goes to logcat.
    @Test
    fun theCoreLoadsAndGivesTheGoldenInfohash() {
        val lines = LinkedBlockingQueue<String>()
        val engine =
            JsEngine(libraryUserAgent()) { level, message ->
                JsLog.logcat.write(level, message)
                lines.put(message)
            }
        try {
            assertTrue(
                engine.post {
                    engine.evaluateScript(
                        "const start = performance.now();" +
                            "import('p2p-media-loader-core').then((core) => {" +
                            "  const loaded = performance.now() - start;" +
                            "  const id = core.buildStreamSwarmId(" +
                            "    'https://example.com/hls/master.m3u8', 'main0');" +
                            "  console.log('core ' + core.computeInfoHash(id) + ' loaded in ' +" +
                            "    loaded.toFixed(1) + ' ms');" +
                            "}, (error) => console.error('core failed: ' + error));",
                        "device.js",
                    )
                }
            )
            val line = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), "no core line")
            // The golden vector of the web repo's stream-identity.test.ts.
            assertTrue(line.startsWith("core W1b9WgA8SSH2c5Dblnh5 loaded in "), line)
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
