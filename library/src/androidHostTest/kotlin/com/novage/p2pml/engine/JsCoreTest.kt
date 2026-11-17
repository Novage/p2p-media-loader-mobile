package com.novage.p2pml.engine

import com.novage.p2pml.P2pMediaLoader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The web core in the engine through JNI, with the library's user agent. The core's own checks are
// in native/test/js/core_test.js.
class JsCoreTest {
    private val userAgent = libraryUserAgent(release = "12")
    private val lines = LinkedBlockingQueue<Pair<JsLog.Level, String>>()
    private val engine = JsEngine(userAgent) { level, message -> lines.put(level to message) }

    @AfterTest
    fun closeEngine() {
        engine.close()
        assertTrue(engine.awaitClosed(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun theUserAgentNamesTheLibraryAndAndroid() {
        assertEquals("P2PMediaLoaderMobile/${P2pMediaLoader.version} (Android 12)", userAgent)
    }

    @Test
    fun theCoreLoadsAndItsLogLinesHaveNoColourCodes() {
        assertTrue(
            engine.post {
                engine.evaluateScript(
                    """
                    import("p2p-media-loader-core").then((core) => {
                      core.debug.enable("p2pml-core:*");
                      const c = new core.Core({ manifestParsers: [core.hlsManifestParser] });
                      c.processManifest({
                        url: "https://example.com/hls/master.m3u8",
                        data: "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nvideo/360p.m3u8\n",
                      });
                      console.log("streams", navigator.userAgent,
                        c.getStreams().map((s) => s.streamId + "=" + s.infoHash).join(" "));
                      c.destroy();
                    });
                    """
                        .trimIndent(),
                    "core.js",
                )
            }
        )
        val debugLines = mutableListOf<String>()
        var streams: String? = null
        while (streams == null) {
            val (level, message) =
                assertNotNull(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), "no streams line")
            when {
                level == JsLog.Level.DEBUG -> debugLines += message
                message.startsWith("streams ") -> streams = message
                else -> error("unexpected line: $message")
            }
        }
        // The golden vector of the web repo's stream-identity.test.ts.
        assertEquals("streams $userAgent main0=W1b9WgA8SSH2c5Dblnh5", streams)
        assertTrue(debugLines.isNotEmpty(), "no debug line")
        for (line in debugLines) {
            assertTrue(line.startsWith("p2pml-core:"), line)
            assertFalse(line.contains("%c"), line)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
