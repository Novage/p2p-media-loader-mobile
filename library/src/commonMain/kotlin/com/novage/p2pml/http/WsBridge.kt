package com.novage.p2pml.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The events of the sockets that [WsBridge] runs, from its threads. Each socket gives an open, any
 * messages, then a close; or a failed close at any point. Each returns whether the engine took the
 * event, as [HttpEvents]. See specs/engine.md, "The platform's network clients".
 */
internal interface WsEvents {
    /** [protocol] and [extensions] are the server's, or empty. */
    fun open(id: Int, protocol: String, extensions: String): Boolean

    /** [bytes] are UTF-8 text unless [binary]. */
    fun message(id: Int, binary: Boolean, bytes: ByteArray): Boolean

    /**
     * [code] and the UTF-8 [reason] of the close frame (1005 and empty when it had none); [failed]
     * when the connection failed (then the code is 1006 and [wasClean] is false).
     */
    fun close(id: Int, code: Int, reason: ByteArray, wasClean: Boolean, failed: Boolean): Boolean
}

/**
 * The sockets of `WebSocket` over Ktor, as [HttpBridge] runs the requests of `fetch`: the engine
 * opens, sends to, closes and cancels them on its thread, and gets their events through [events].
 * The handshake sends [userAgent] as its User-Agent.
 */
internal class WsBridge(
    client: () -> HttpClient,
    private val userAgent: String,
    private val events: WsEvents,
    private val onEngine: (() -> Unit) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val client by lazy(client)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    // Engine thread only.
    private val sockets = HashMap<Int, Socket>()

    // The frames that JS sends go through [frames]: the session exists only once it is open.
    private class Socket(val job: Job, val frames: Channel<Frame>)

    /** Engine thread. */
    fun open(id: Int, url: String, protocols: List<String>) {
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val job = scope.launch { run(id, url, protocols, frames) }
        sockets[id] = Socket(job, frames)
        // A later task than this one, so the socket is in the map by then.
        job.invokeOnCompletion { onEngine { sockets.remove(id) } }
    }

    /** Engine thread. [bytes] are UTF-8 text unless [binary]. */
    fun send(id: Int, binary: Boolean, bytes: ByteArray) {
        sockets[id]
            ?.frames
            ?.trySend(if (binary) Frame.Binary(true, bytes) else Frame.Text(true, bytes))
    }

    /**
     * Engine thread. Starts the closing handshake with [code] (-1 for none: OkHttp always sends a
     * code, so it sends 1000) and the UTF-8 [reason].
     */
    fun close(id: Int, code: Int, reason: ByteArray) {
        val closeCode = if (code < 0) CloseReason.Codes.NORMAL.code else code.toShort()
        sockets[id]?.frames?.trySend(Frame.Close(CloseReason(closeCode, reason.decodeToString())))
    }

    /** Engine thread. No event of the socket follows, except one already on its way. */
    fun cancel(id: Int) {
        sockets.remove(id)?.job?.cancel()
    }

    /** Cancels every socket. Any thread. */
    fun close() {
        scope.cancel()
    }

    // A failure of any kind is a failed close: JS gets error, then close 1006.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(id: Int, url: String, protocols: List<String>, frames: Channel<Frame>) {
        var end: CloseReason? = null
        try {
            client.webSocket(
                urlString = url,
                request = {
                    header(HttpHeaders.UserAgent, userAgent)
                    if (protocols.isNotEmpty()) {
                        header(HttpHeaders.SecWebSocketProtocol, protocols.joinToString(", "))
                    }
                },
            ) {
                if (!deliver(id, frames)) {
                    // The engine did not take an event: the socket closes, so that JS never
                    // misses a message.
                    fail(id)
                    return@webSocket
                }
                // Completed when the server's close frame arrives; it throws when the connection
                // failed.
                end = closeReason.await() ?: CloseReason(NO_STATUS, "")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fail(id)
            return
        }
        // A refused close would leave the socket open in JS for good: then a failed close.
        val reason = end ?: return
        if (
            !events.close(id, reason.code.toInt(), reason.message.encodeToByteArray(), true, false)
        ) {
            fail(id)
        }
    }

    // Gives the open, then each message, until the server's close; false when the engine refuses
    // one.
    private suspend fun DefaultClientWebSocketSession.deliver(id: Int, frames: Channel<Frame>) =
        coroutineScope {
            val headers = call.response.headers
            var taken =
                events.open(
                    id,
                    headers[HttpHeaders.SecWebSocketProtocol].orEmpty(),
                    headers[HttpHeaders.SecWebSocketExtensions].orEmpty(),
                )
            // In this scope, so that a cancel of the socket stops it.
            val writer = launch { for (frame in frames) outgoing.send(frame) }
            // The channel ends at the server's close frame; it throws when the connection fails.
            while (taken) {
                val frame = incoming.receiveCatching().getOrNull() ?: break
                taken =
                    when (frame) {
                        is Frame.Text -> events.message(id, false, frame.data)
                        is Frame.Binary -> events.message(id, true, frame.data)
                        else -> true
                    }
            }
            writer.cancel()
            taken
        }

    // A failed close. The engine can refuse it too; then the socket gives no more events.
    private fun fail(id: Int) {
        events.close(id, ABNORMAL, ByteArray(0), wasClean = false, failed = true)
    }

    private companion object {
        const val ABNORMAL = 1006
        const val NO_STATUS: Short = 1005
    }
}
