package com.novage.p2pml.http

import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer

// HttpBridge over the production client (SharedHttpClient: Ktor's OkHttp engine) and a real local
// server. One thread stands in for the engine thread.
class HttpBridgeTest {
    private val server = MockWebServer().apply { start() }
    private val engineThread = Executors.newSingleThreadExecutor()
    private val events = RecordedEvents()

    private val bridge =
        HttpBridge(
            { SharedHttpClient.client },
            events,
            onEngine = { engineThread.execute(it) },
        )

    @AfterTest
    fun stop() {
        bridge.close()
        engineThread.shutdownNow()
        server.close()
    }

    private fun onEngine(block: () -> Unit) = engineThread.submit(block).get()

    private fun start(url: String, headers: List<String> = emptyList(), method: String = "GET") =
        onEngine {
            bridge.start(1, method, url, headers)
        }

    @Test
    fun aThrottledBodyArrivesInChunks() {
        val body = Buffer().write(ByteArray(BODY_BYTES))
        server.enqueue(
            MockResponse.Builder()
                .body(body)
                .throttleBody(BODY_BYTES / CHUNKS, PERIOD_MILLISECONDS, TimeUnit.MILLISECONDS)
                .build()
        )
        start(server.url("/segment").toString())
        val all = events.all()
        assertEquals("response 200 ${server.url("/segment")}", all.first())
        assertEquals("end", all.last())
        assertTrue(all.count { it.startsWith("data") } > 1, "$all")
        assertEquals(BODY_BYTES, events.bytes)
    }

    @Test
    fun theHeadersOfTheRequestAreSent() {
        server.enqueue(MockResponse.Builder().code(PARTIAL_CONTENT).body("x").build())
        start(server.url("/a").toString(), listOf("Range", "bytes=0-9", "User-Agent", "agent"))
        events.all()
        val request = server.takeRequest()
        assertEquals("bytes=0-9", request.headers["Range"])
        assertEquals("agent", request.headers["User-Agent"])
    }

    @Test
    fun aCancelStopsTheEvents() {
        val body = Buffer().write(ByteArray(BODY_BYTES))
        server.enqueue(
            MockResponse.Builder()
                .body(body)
                .throttleBody(SLOW_BYTES, PERIOD_MILLISECONDS, TimeUnit.MILLISECONDS)
                .build()
        )
        start(server.url("/slow").toString())
        assertTrue(events.next().startsWith("response"))
        assertTrue(events.next().startsWith("data"))
        onEngine { bridge.cancel(1) }
        // At most one chunk that was already on its way, then nothing.
        events.queue.poll(CANCEL_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS)
        assertNull(events.queue.poll(CANCEL_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS))
        assertTrue(events.bytes < BODY_BYTES)
    }

    @Test
    fun aRefusedEventStopsTheRequestWithAFailure() {
        // No event after the refused one: JS never gets a body with a gap.
        for ((refused, expected) in
            listOf(
                "data" to listOf("refused", "fail"),
                "end" to listOf("data 1", "refused", "fail"),
            )) {
            events.refuse = refused
            server.enqueue(MockResponse.Builder().body("x").build())
            start(server.url("/segment").toString())
            assertEquals(listOf("response 200 ${server.url("/segment")}") + expected, events.all())
        }
    }

    @Test
    fun aBodyThatTheNetworkCutsOffIsAFailure() {
        server.enqueue(
            MockResponse.Builder()
                .chunkedBody(Buffer().write(ByteArray(BODY_BYTES)), CHUNK_BYTES)
                .onResponseBody(SocketEffect.CloseSocket())
                .build()
        )
        start(server.url("/cut").toString())
        // Not an end: the core would take a truncated body as complete.
        assertEquals("fail", events.all().last())
        assertTrue(events.bytes < BODY_BYTES)
    }

    @Test
    fun aRedirectIsFollowed() {
        server.enqueue(MockResponse.Builder().code(FOUND).addHeader("Location", "/b").build())
        server.enqueue(MockResponse.Builder().body("b").build())
        start(server.url("/a").toString())
        assertEquals(listOf("response 200 ${server.url("/b")}", "data 1", "end"), events.all())
    }

    @Test
    fun a404IsAResponse() {
        server.enqueue(MockResponse.Builder().code(NOT_FOUND).build())
        start(server.url("/missing").toString(), method = "HEAD")
        assertEquals(listOf("response 404 ${server.url("/missing")}", "end"), events.all())
    }

    @Test
    fun aRefusedConnectionIsAFailure() {
        val url = server.url("/a").toString()
        server.close()
        start(url)
        assertEquals(listOf("fail"), events.all())
    }

    private companion object {
        const val BODY_BYTES = 200 * 1024
        const val CANCEL_WAIT_MILLISECONDS = 300L
        const val CHUNKS = 8L
        const val CHUNK_BYTES = 4096
        const val SLOW_BYTES = 1024L
        const val PERIOD_MILLISECONDS = 20L
        const val PARTIAL_CONTENT = 206
        const val FOUND = 302
        const val NOT_FOUND = 404
    }
}

// The events of the bridge, as lines such as "data 1024", for the tests to read in order.
private class RecordedEvents : HttpEvents {
    val queue = LinkedBlockingQueue<String>()
    @Volatile var bytes = 0
    // The kind of event that the engine refuses ("data" or "end"), as when it is out of memory.
    @Volatile var refuse: String? = null

    fun next(): String = assertNotNull(queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    // The events up to an end or a failure.
    fun all(): List<String> {
        val all = mutableListOf<String>()
        do all += next() while (all.last() != "end" && all.last() != "fail")
        return all
    }

    override fun response(
        id: Int,
        status: Int,
        statusText: String,
        url: String,
        headers: List<String>,
    ): Boolean = queue.add("response $status $url")

    override fun data(id: Int, bytes: ByteArray, length: Int): Boolean {
        if (refuse == "data") return refused()
        this.bytes += length
        return queue.add("data $length")
    }

    override fun end(id: Int): Boolean = if (refuse == "end") refused() else queue.add("end")

    override fun fail(id: Int, message: String): Boolean = queue.add("fail")

    private fun refused(): Boolean {
        queue.add("refused")
        return false
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
