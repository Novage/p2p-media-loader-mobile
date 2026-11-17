package com.novage.p2pml.engine

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

// fetch end to end: the engine, the JNI glue, HttpBridge and a real local server. The core
// downloads the playlists and a segment through it. The rules of fetch are tested in
// native/test/js/fetch_test.js.
class JsFetchTest {
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/master.m3u8" -> MockResponse.Builder().body(MASTER).build()
                            "/media.m3u8" -> MockResponse.Builder().body(MEDIA).build()
                            "/many" ->
                                MockResponse.Builder()
                                    .apply {
                                        repeat(MANY_HEADERS) { addHeader("X-Header-$it", "$it") }
                                    }
                                    .build()
                            "/seg0.ts" ->
                                MockResponse.Builder()
                                    .body(Buffer().write(ByteArray(SEGMENT_BYTES)))
                                    .build()
                            else -> MockResponse.Builder().code(NOT_FOUND).build()
                        }
                }
            start()
        }
    private val lines = LinkedBlockingQueue<Pair<JsLog.Level, String>>()
    private val engine = JsEngine("test-agent") { level, message -> lines.put(level to message) }

    @AfterTest
    fun stop() {
        engine.close()
        assertEquals(true, engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        server.close()
    }

    @Test
    fun theCoreLoadsASegmentThroughFetch() {
        val master = server.url("/master.m3u8")
        engine.post {
            engine.evaluateScript(
                """
                import("p2p-media-loader-core").then(async (core) => {
                  const c = new core.Core({ manifestParsers: [core.hlsManifestParser] });
                  try {
                    for (const url of ["$master", "${server.url("/media.m3u8")}"]) {
                      const response = await fetch(url);
                      c.processManifest({ url: response.url, data: await response.text() });
                    }
                    c.updatePlayback({ bufferAhead: 0, rate: 1 });
                    const loaded = await c.loadSegment("${server.url("/seg0.ts")}");
                    console.log("loaded " + loaded.data.byteLength);
                  } finally {
                    c.destroy();
                  }
                }).catch((error) => console.error("failed: " + error));
                """
                    .trimIndent(),
                "fetch.js",
            )
        }
        val (level, message) = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(JsLog.Level.INFO to "loaded $SEGMENT_BYTES", level to message)
        assertEquals("test-agent", server.takeRequest().headers["User-Agent"])
    }

    // More headers than the 512 local references that Android 7 allows a JNI call: jni.c copies
    // each header string and frees its reference at once. The JVM does not enforce that limit, so
    // this checks that the copies are right; the limit itself is checked by reading jni.c.
    @Test
    fun aResponseWithManyHeadersArrives() {
        engine.post {
            engine.evaluateScript(
                "fetch('${server.url("/many")}').then((response) => " +
                    "console.log('headers ' + [...response.headers.keys()].length + ' ' + " +
                    "response.headers.get('x-header-${MANY_HEADERS - 1}')))",
                "many.js",
            )
        }
        val (_, message) = assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        // Each response also has OkHttp's Content-Length header.
        assertEquals("headers ${MANY_HEADERS + 1} ${MANY_HEADERS - 1}", message)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val MANY_HEADERS = 600
        const val SEGMENT_BYTES = 300 * 1024
        const val NOT_FOUND = 404
        const val MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n"
        const val MEDIA =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXT-X-ENDLIST\n"
    }
}
