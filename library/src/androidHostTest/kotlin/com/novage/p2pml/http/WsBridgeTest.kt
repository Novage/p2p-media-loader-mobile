package com.novage.p2pml.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val TIMEOUT_SECONDS = 10L

// WsBridge over the production client (SharedHttpClient: Ktor's OkHttp engine) and a real local
// server. One thread stands in for the engine thread.
// One test for each rule of the bridge.
@Suppress("TooManyFunctions")
class WsBridgeTest {
    private val server = MockWebServer().apply { start() }
    private val engineThread = Executors.newSingleThreadExecutor()
    private val events = RecordedWsEvents()
    // A client of a test's own, in place of the shared one; stop() closes it.
    private var ownClient: HttpClient? = null

    private val bridge by lazy {
        WsBridge(
            { ownClient ?: SharedHttpClient.client },
            "test-agent",
            events,
            onEngine = { engineThread.execute(it) },
        )
    }

    @AfterTest
    fun stop() {
        bridge.close()
        ownClient?.close()
        engineThread.shutdownNow()
        server.close()
    }

    private fun onEngine(block: () -> Unit) = engineThread.submit(block).get()

    private fun url() = server.url("/tracker").toString().replaceFirst("http", "ws")

    private fun open(protocols: List<String> = emptyList()) = onEngine {
        bridge.open(1, url(), protocols)
    }

    private fun upgrade(listener: WebSocketListener, protocol: String? = null) =
        server.enqueue(
            MockResponse.Builder()
                .apply { protocol?.let { addHeader("Sec-WebSocket-Protocol", it) } }
                .webSocketUpgrade(listener)
                .build()
        )

    @Test
    fun textAndBinaryAreEchoedThenTheClientCloses() {
        upgrade(EchoListener())
        open()
        assertEquals("open |", events.next())
        onEngine {
            bridge.send(1, false, "h\u00e9 \uD83D\uDE00".encodeToByteArray())
            bridge.send(1, true, byteArrayOf(1, 2))
        }
        assertEquals("text h\u00e9 \uD83D\uDE00", events.next())
        assertEquals("binary 01 02", events.next())
        onEngine { bridge.close(1, CLOSE_CODE, "bye".encodeToByteArray()) }
        assertEquals("close $CLOSE_CODE bye clean", events.next())
    }

    @Test
    fun theClientsCloseWithNoCodeSends1000() {
        upgrade(EchoListener())
        open()
        events.next()
        onEngine { bridge.close(1, -1, ByteArray(0)) }
        assertEquals("close 1000  clean", events.next())
    }

    @Test
    fun theServerCloses() {
        upgrade(
            object : EchoListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("hi")
                    webSocket.close(CLOSE_CODE, "done")
                }
            }
        )
        open()
        assertEquals(listOf("open |", "text hi", "close $CLOSE_CODE done clean"), events.all())
    }

    @Test
    fun aRefusedCleanCloseIsAFailedClose() {
        events.refuse = "clean"
        upgrade(
            object : EchoListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(CLOSE_CODE, "done")
                }
            }
        )
        open()
        // Not nothing: JS would wait for a close for good.
        assertEquals(listOf("open |", "refused", "close 1006  failed"), events.all())
    }

    @Test
    fun theHandshakeSendsTheUserAgentAndTheSubprotocols() {
        upgrade(EchoListener(), protocol = "chat")
        open(listOf("chat", "other"))
        assertEquals("open chat|", events.next())
        val request = server.takeRequest()
        assertEquals("test-agent", request.headers["User-Agent"])
        assertEquals("chat, other", request.headers["Sec-WebSocket-Protocol"])
    }

    @Test
    fun aRefusedConnectionIsAFailedClose() {
        val url = url()
        server.close()
        onEngine { bridge.open(1, url, emptyList()) }
        assertEquals(listOf("close 1006  failed"), events.all())
    }

    @Test
    fun aDroppedConnectionIsAFailedClose() {
        // The server drops the connection at the first message, so that the open comes first.
        upgrade(
            object : EchoListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = webSocket.cancel()
            }
        )
        open()
        assertEquals("open |", events.next())
        onEngine { bridge.send(1, false, "x".encodeToByteArray()) }
        assertEquals("close 1006  failed", events.next())
    }

    @Test
    fun aRefusedEventClosesTheSocketWithAFailure() {
        events.refuse = "text"
        upgrade(
            object : EchoListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("a")
                    webSocket.send("b")
                }
            }
        )
        open()
        // No message after the refused one: JS never misses a message and goes on.
        assertEquals(listOf("open |", "refused", "close 1006  failed"), events.all())
    }

    @Test
    fun aCancelClosesTheSocketAndStopsTheEvents() {
        val serverSaw = LinkedBlockingQueue<String>()
        upgrade(
            object : EchoListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    serverSaw.add("closing")
                    super.onClosing(webSocket, code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    serverSaw.add("failure")
                }
            }
        )
        open()
        events.next()
        onEngine {
            bridge.send(1, false, "x".encodeToByteArray())
            bridge.cancel(1)
        }
        assertNotNull(serverSaw.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        // At most the echo that was already on its way, then nothing.
        events.queue.poll(WAIT_MILLISECONDS, TimeUnit.MILLISECONDS)
        assertNull(events.queue.poll(WAIT_MILLISECONDS, TimeUnit.MILLISECONDS))
    }

    // A tracker can be silent for minutes: OkHttp's read timeout does not count the time between
    // frames.
    @Test
    fun aSilentSocketOutlivesTheReadTimeout() {
        ownClient =
            HttpClient(OkHttp) {
                install(WebSockets)
                engine { config { readTimeout(SHORT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS) } }
            }
        upgrade(EchoListener())
        open()
        events.next()
        assertNull(events.queue.poll(SILENCE_MILLISECONDS, TimeUnit.MILLISECONDS))
        onEngine { bridge.send(1, false, "still".encodeToByteArray()) }
        assertEquals("text still", events.next())
    }

    private companion object {
        const val CLOSE_CODE = 4000
        const val WAIT_MILLISECONDS = 300L
        const val SHORT_TIMEOUT_MILLISECONDS = 500L
        // Three times the read timeout.
        const val SILENCE_MILLISECONDS = 1500L
    }
}

// The events of the bridge, as lines such as "text hi", for the tests to read in order.
private class RecordedWsEvents : WsEvents {
    val queue = LinkedBlockingQueue<String>()
    // The kind of event that the engine refuses ("text", or "clean" for a clean close), as when it
    // is out of memory.
    @Volatile var refuse: String? = null

    fun next(): String = assertNotNull(queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    // The events up to a close.
    fun all(): List<String> {
        val all = mutableListOf<String>()
        do all += next() while (!all.last().startsWith("close"))
        return all
    }

    override fun open(id: Int, protocol: String, extensions: String): Boolean =
        queue.add("open $protocol|$extensions")

    override fun message(id: Int, binary: Boolean, bytes: ByteArray): Boolean {
        val kind = if (binary) "binary" else "text"
        if (kind == refuse) {
            queue.add("refused")
            return false
        }
        val text =
            if (binary) bytes.joinToString(" ") { it.toUByte().toString(HEX).padStart(2, '0') }
            else bytes.decodeToString()
        return queue.add("$kind $text")
    }

    override fun close(
        id: Int,
        code: Int,
        reason: ByteArray,
        wasClean: Boolean,
        failed: Boolean,
    ): Boolean {
        val how = if (failed) "failed" else if (wasClean) "clean" else "not clean"
        if (refuse == how) {
            queue.add("refused")
            return false
        }
        return queue.add("close $code ${reason.decodeToString()} $how")
    }

    private companion object {
        const val HEX = 16
    }
}
