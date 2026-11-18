package com.novage.p2pml.engine

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

// The phone's core and a Node peer (tools/node-peer) in one test swarm: the phone plays a public
// live stream through its core and must download bytes from the Node peer and upload bytes to it.
// It runs only with the instrumentation argument `swarm`, the ID that the Node peer gets with
// `--swarm`; the optional `minutes` (default 4) and `stream` (node-peer's default stream) change
// the run. It needs the internet, and a network on which the two peers can connect.
class NodePeerDeviceTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val lines = LinkedBlockingQueue<String>()
    private val engine =
        JsEngine(libraryUserAgent()) { level, message ->
            JsLog.logcat.write(level, message)
            lines.put(message)
        }

    @AfterTest
    fun stop() {
        engine.close()
        assertTrue(engine.awaitClosed(CLOSE_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun thePhoneAndTheNodePeerExchangeSegments() {
        // An empty ID is no ID, as the Node peer reads an empty --swarm.
        val swarm = arguments.getString("swarm").orEmpty()
        assumeTrue("no swarm argument: no Node peer to meet", swarm.isNotEmpty())
        val minutes = arguments.getString("minutes")?.toLong() ?: DEFAULT_MINUTES
        val stream = arguments.getString("stream") ?: DEFAULT_STREAM
        val script =
            NodePeerScripts.driver(
                swarm,
                stream,
                TimeUnit.MINUTES.toSeconds(minutes),
            )
        assertTrue(engine.post { engine.evaluateScript(script, "node-peer.js") })

        // The driver's timer starts after the playlist fetches, which can take a minute.
        val end = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes + 2)
        val line =
            assertNotNull(
                generateSequence { lines.poll(end - System.nanoTime(), TimeUnit.NANOSECONDS) }
                    .firstOrNull { it.startsWith("summary ") || it.startsWith("failed: ") },
                "no summary",
            )
        assertTrue(line.startsWith("summary "), line)
        val counts =
            Regex("""(\w+) (\d+)""").findAll(line).associate {
                it.groupValues[1] to it.groupValues[2].toLong()
            }
        assertTrue(counts.getValue("p2p") > 0, "no bytes from the Node peer: $line")
        assertTrue(counts.getValue("uploaded") > 0, "no bytes to the Node peer: $line")
    }

    private companion object {
        const val CLOSE_SECONDS = 15L
        const val DEFAULT_MINUTES = 4L
        const val DEFAULT_STREAM = "https://live.novage.workers.dev/bbb/live.m3u8"
    }
}
