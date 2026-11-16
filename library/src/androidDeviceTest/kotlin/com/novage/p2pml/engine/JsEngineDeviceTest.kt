package com.novage.p2pml.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The native library on a device's ABI. The rules are tested on the JVM (JsEngineTest).
class JsEngineDeviceTest {
    @Test
    fun theEngineRunsAScript() {
        val engine = JsEngine()
        try {
            val result = CompletableFuture<String>()
            assertTrue(
                engine.post {
                    runCatching { engine.evaluateScript("6 * 7", "device.js") }
                        .fold(result::complete, result::completeExceptionally)
                }
            )
            assertEquals("42", result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            engine.close()
        }
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
