package com.novage.p2pml.engine

import com.novage.p2pml.http.EchoListener
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
import okio.Buffer

// WebSocket end to end: the engine, the JNI glue, WsBridge and a real local server, which is an
// echo server and a tracker. The rules of WebSocket are tested in native/test/js/websocket_test.js.
class JsWebSocketTest {
    // The tracker's first message from each socket.
    private val announces = LinkedBlockingQueue<String>()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/echo" ->
                                MockResponse.Builder().webSocketUpgrade(EchoListener()).build()
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
    private val engine = JsEngine("test-agent") { _, message -> lines.put(message) }

    @AfterTest
    fun stop() {
        engine.close()
        assertEquals(true, engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        server.close()
    }

    private fun wsUrl(path: String) = server.url(path).toString().replaceFirst("http", "ws")

    private fun nextLine() = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    @Test
    fun textAndBinaryGoBothWays() {
        engine.post {
            engine.evaluateScript(
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
                    socket.close(4000);
                  }
                };
                socket.onclose = (event) =>
                  console.log(`close ${'$'}{event.code} ${'$'}{event.reason} ${'$'}{event.wasClean}`);
                """
                    .trimIndent(),
                "echo.js",
            )
        }
        assertEquals("text h\u00e9 \uD83D\uDE00", nextLine())
        assertEquals("binary 1,2,3", nextLine())
        // An empty reason crosses JNI too.
        assertEquals("close 4000  true", nextLine())
        assertEquals("test-agent", server.takeRequest().headers["User-Agent"])
    }

    // The core opens its tracker socket at the first segment request and announces the stream.
    // Without WebRTC it turns P2P off, so the test gives it a stub: its first announce needs no
    // peer connection.
    @Test
    fun theCoreAnnouncesToItsTracker() {
        engine.post {
            engine.evaluateScript(
                """
                window.RTCPeerConnection = class {
                  createOffer() { return Promise.resolve({ type: "offer", sdp: "" }); }
                  close() {}
                };
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
                    .trimIndent(),
                "core.js",
            )
        }
        // JSON.stringify's text, with no spaces.
        val announce = assertNotNull(announces.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(announce.startsWith("{\"action\":\"announce\","), announce)
        assertTrue(announce.contains("\"event\":\"started\""), announce)
        assertTrue(Regex("\"info_hash\":\"[^\"]+\"").containsMatchIn(announce), announce)
        engine.post { engine.evaluateScript("globalThis.core.destroy()", "destroy.js") }
    }

    private inner class Tracker : EchoListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            announces.add(text)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val SEGMENT_BYTES = 1024
        const val NOT_FOUND = 404
        const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n"
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXT-X-ENDLIST\n"
    }
}
