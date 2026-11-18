package com.novage.p2pml.engine

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// WebRTC end to end: the engine, its binding of libdatachannel in the JNI library, and two peer
// connections in one engine that move 4 MiB over the loopback. The rules of the polyfill are
// tested in native/test/js/webrtc_test.js.
class JsWebRtcTest {
    private val lines = LinkedBlockingQueue<String>()
    private val engine = JsEngine("test-agent") { _, message -> lines.put(message) }

    @AfterTest
    fun stop() {
        engine.close()
        assertEquals(true, engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun twoPeersMoveFourMebibytes() {
        engine.post { engine.evaluateScript(WebRtcScripts.TRANSFER, "webrtc.js") }
        val line = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(line.startsWith("webrtc: ${WebRtcScripts.BYTES} bytes at "), line)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
