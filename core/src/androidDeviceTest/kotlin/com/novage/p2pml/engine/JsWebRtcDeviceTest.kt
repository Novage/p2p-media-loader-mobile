package com.novage.p2pml.engine

import com.novage.p2pml.core.libraryUserAgent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// WebRTC on a device's ABI: two peer connections in one engine move 4 MiB through the binding of
// libdatachannel; the throughput goes to logcat. The device needs a network interface up: the
// Android build gathers no loopback candidates.
class JsWebRtcDeviceTest {
    private val lines = LinkedBlockingQueue<String>()
    private val engine =
        JsEngine(libraryUserAgent()) { level, message ->
            JsLog.logcat.write(level, message)
            lines.put(message)
        }

    @AfterTest
    fun stop() {
        engine.close()
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun twoPeersMoveFourMebibytesAndAThroughputIsLogged() {
        assertTrue(engine.post { engine.evaluateScript(WebRtcScripts.TRANSFER, "webrtc.js") })
        val line = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(line.startsWith("webrtc: ${WebRtcScripts.BYTES} bytes at "), line)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
