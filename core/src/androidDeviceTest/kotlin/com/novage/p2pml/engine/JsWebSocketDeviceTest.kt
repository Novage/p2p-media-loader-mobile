package com.novage.p2pml.engine

import android.util.Log
import com.novage.p2pml.core.libraryUserAgent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString

// WebSocket on a device's ABI, with Ktor's OkHttp engine on Android, to a local server on the
// device that echoes and is a tracker. A connect to a public tracker over TLS goes to logcat only:
// it needs the network.
class JsWebSocketDeviceTest {
    private val announces = LinkedBlockingQueue<String>()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/echo" -> MockResponse.Builder().webSocketUpgrade(Echo()).build()
                            "/tracker" -> MockResponse.Builder().webSocketUpgrade(Tracker()).build()
                            "/master.m3u8" -> MockResponse.Builder().body(MASTER).build()
                            "/media.m3u8" -> MockResponse.Builder().body(MEDIA).build()
                            "/seg0.ts" ->
                                MockResponse.Builder()
                                    .body(Buffer().write(ByteArray(SEGMENT_BYTES)))
                                    .build()
                            else -> MockResponse.Builder().code(NOT_FOUND).build()
                        }
                }
            start()
        }
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
        server.close()
    }

    private fun wsUrl(path: String) = server.url(path).toString().replaceFirst("http", "ws")

    private fun evaluate(code: String) =
        assertTrue(engine.post { engine.evaluateScript(code.trimIndent(), "device.js") })

    @Test
    fun textAndBinaryGoBothWays() {
        evaluate(
            """
            const socket = new WebSocket("${wsUrl("/echo")}");
            socket.binaryType = "arraybuffer";
            socket.onopen = () => {
              socket.send("h\u00e9 \u{1F600}");
              socket.send(new Uint8Array([1, 2, 3]));
            };
            socket.onmessage = ({ data }) => {
              if (typeof data === "string") console.log("text " + data);
              else {
                console.log("binary " + new Uint8Array(data).join());
                socket.close(4000, "bye");
              }
            };
            socket.onclose = (event) =>
              console.log(`close ${'$'}{event.code} ${'$'}{event.reason} ${'$'}{event.wasClean}`);
            """
        )
        assertEquals("text h\u00e9 \uD83D\uDE00", next())
        assertEquals("binary 1,2,3", next())
        assertEquals("close 4000 bye true", next())
    }

    // As JsWebSocketTest: the engine's RTCPeerConnection turns the core's P2P on.
    @Test
    fun theCoreAnnouncesToItsTracker() {
        evaluate(
            """
            import("p2p-media-loader-core").then(async (core) => {
              const c = new core.Core({
                manifestParsers: [core.hlsManifestParser],
                announceTrackers: ["${wsUrl("/tracker")}"],
              });
              // Before the first await, so that the test can destroy it after the announce.
              globalThis.core = c;
              for (const url of ["${server.url("/master.m3u8")}", "${server.url("/media.m3u8")}"]) {
                const response = await fetch(url);
                c.processManifest({ url: response.url, data: await response.text() });
              }
              c.updatePlayback({ bufferAhead: 0, rate: 1 });
              await c.loadSegment("${server.url("/seg0.ts")}");
            }).catch((error) => console.error("failed: " + error));
            """
        )
        val announce = assertNotNull(announces.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(announce.startsWith("{\"action\":\"announce\","), announce)
        assertTrue(announce.contains("\"event\":\"started\""), announce)
        evaluate("globalThis.core.destroy()")
    }

    // Logged only, not a condition of the test: it needs the network.
    @Test
    fun aConnectToAPublicTrackerIsLogged() {
        evaluate(
            """
            const start = performance.now();
            const socket = new WebSocket("$PUBLIC_TRACKER");
            socket.onopen = () => {
              console.log("public tracker: open in " + Math.round(performance.now() - start) +
                " ms");
              socket.close();
            };
            socket.onclose = (event) => console.log("public tracker: close " + event.code);
            """
        )
        val opened = lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Log.i(TAG, opened ?: "public tracker: no event in $TIMEOUT_SECONDS s")
    }

    private fun next() = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    private open class Echo : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocket.send(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            webSocket.send(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    private inner class Tracker : Echo() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            announces.add(text)
        }
    }

    private companion object {
        const val TAG = "JsWebSocketDeviceTest"
        const val TIMEOUT_SECONDS = 15L
        const val SEGMENT_BYTES = 1024
        const val NOT_FOUND = 404
        const val PUBLIC_TRACKER = "wss://tracker.novage.com.ua"
        const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n"
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXT-X-ENDLIST\n"
    }
}
