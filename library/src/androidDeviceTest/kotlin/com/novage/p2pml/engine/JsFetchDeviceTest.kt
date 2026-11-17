package com.novage.p2pml.engine

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
import okio.Buffer

// fetch on a device's ABI, with Ktor's OkHttp engine on Android, from a local server on the
// device. The throughput of a large body through the bridge goes to logcat.
class JsFetchDeviceTest {
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/master.m3u8" -> MockResponse.Builder().body(MASTER).build()
                            "/media.m3u8" -> MockResponse.Builder().body(MEDIA).build()
                            "/seg0.ts" -> bytes(SEGMENT_BYTES)
                            "/large" -> bytes(LARGE_BYTES)
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

    @Test
    fun theCoreLoadsASegmentAndAThroughputIsLogged() {
        assertTrue(
            engine.post {
                engine.evaluateScript(
                    """
                    import("p2p-media-loader-core").then(async (core) => {
                      const c = new core.Core({ manifestParsers: [core.hlsManifestParser] });
                      try {
                        for (const url of ["${server.url("/master.m3u8")}",
                                           "${server.url("/media.m3u8")}"]) {
                          const response = await fetch(url);
                          c.processManifest({ url: response.url, data: await response.text() });
                        }
                        c.updatePlayback({ bufferAhead: 0, rate: 1 });
                        const loaded = await c.loadSegment("${server.url("/seg0.ts")}");
                        console.log("loaded " + loaded.data.byteLength);
                      } finally {
                        c.destroy();
                      }
                      const start = performance.now();
                      const reader = (await fetch("${server.url("/large")}")).body.getReader();
                      let bytes = 0;
                      for (;;) {
                        const { value, done } = await reader.read();
                        if (done) break;
                        bytes += value.byteLength;
                      }
                      const seconds = (performance.now() - start) / 1000;
                      console.log("fetch: " + bytes + " bytes at " +
                        (bytes / 1048576 / seconds).toFixed(1) + " MiB/s");
                    }).catch((error) => console.error("failed: " + error));
                    """
                        .trimIndent(),
                    "device.js",
                )
            }
        )
        assertEquals("loaded $SEGMENT_BYTES", next())
        val throughput = next()
        assertTrue(throughput.startsWith("fetch: $LARGE_BYTES bytes at "), throughput)
    }

    private fun next() = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val SEGMENT_BYTES = 300 * 1024
        const val LARGE_BYTES = 20 * 1024 * 1024
        const val NOT_FOUND = 404
        const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n"
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXT-X-ENDLIST\n"

        fun bytes(count: Int): MockResponse =
            MockResponse.Builder().body(Buffer().write(ByteArray(count))).build()
    }
}
