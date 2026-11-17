package com.novage.p2pml.http

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The events of the requests that [HttpBridge] runs, from its threads. Each request gives a
 * response, any data, then an end; or a failure at any point. Each returns whether the engine took
 * the event: false once it is closed, or when it cannot copy the event. (A failed JNI allocation
 * throws an OutOfMemoryError instead, which ends the thread, as any Error.) See specs/engine.md,
 * "The platform's HTTP client".
 */
internal interface HttpEvents {
    /** [headers] are names and values, in turn. */
    fun response(
        id: Int,
        status: Int,
        statusText: String,
        url: String,
        headers: List<String>,
    ): Boolean

    /** The first [length] bytes of [bytes], which the caller reuses: copy them before returning. */
    fun data(id: Int, bytes: ByteArray, length: Int): Boolean

    fun end(id: Int): Boolean

    fun fail(id: Int, message: String): Boolean
}

/**
 * The requests of `fetch` over Ktor: the engine starts and cancels them on its thread, and gets
 * their events through [events]. [onEngine] runs a block on the engine thread later; the bridge
 * keeps its requests there. The requests run on [dispatcher]; Ktor's engine does their I/O on its
 * own threads. [client] is read at the first request, on a thread of [dispatcher], so that making a
 * bridge makes no client.
 */
internal class HttpBridge(
    client: () -> HttpClient,
    private val events: HttpEvents,
    private val onEngine: (() -> Unit) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val client by lazy(client)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    // Engine thread only.
    private val requests = HashMap<Int, Job>()

    /** Engine thread. [headers] are names and values, in turn. */
    fun start(id: Int, method: String, url: String, headers: List<String>) {
        val job = scope.launch { run(id, method, url, headers) }
        requests[id] = job
        // A later task than this one, so the request is in the map by then.
        job.invokeOnCompletion { onEngine { requests.remove(id) } }
    }

    /** Engine thread. No event of the request follows, except one already on its way. */
    fun cancel(id: Int) {
        requests.remove(id)?.cancel()
    }

    /** Cancels every request. Any thread. */
    fun close() {
        scope.cancel()
    }

    // A failure of any kind ends the request with its message: the core sees a network error.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(id: Int, method: String, url: String, headers: List<String>) {
        try {
            client
                .prepareRequest {
                    this.method = HttpMethod.parse(method)
                    url(url)
                    for (i in 0 until headers.size - 1 step 2) header(headers[i], headers[i + 1])
                }
                .execute { response -> if (!deliver(id, response)) refused(id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            events.fail(id, error.message ?: error::class.simpleName ?: "error")
        }
    }

    // Gives the response's events, until the engine refuses one: then false.
    private suspend fun deliver(id: Int, response: HttpResponse): Boolean {
        val pairs = buildList {
            response.headers.forEach { name, values ->
                for (value in values) {
                    add(name)
                    add(value)
                }
            }
        }
        return events.response(
            id,
            response.status.value,
            response.status.description,
            response.call.request.url.toString(),
            pairs,
        ) && deliverBody(id, response.bodyAsChannel()) && events.end(id)
    }

    // Gives the body's chunks, until the engine refuses one: then false.
    private suspend fun deliverBody(id: Int, body: ByteReadChannel): Boolean {
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val count = body.readAvailable(buffer, 0, buffer.size)
            // -1 also when the network cut the body off: the cause makes it a failure.
            if (count < 0) return body.closedCause?.let { throw it } ?: true
            if (count > 0 && !events.data(id, buffer, count)) return false
        }
    }

    // The engine did not take an event: the request stops, so that JS never gets a body with a gap.
    // The failure can be refused too; then the request gives no more events.
    private fun refused(id: Int) {
        events.fail(id, "the engine did not take an event")
    }

    private companion object {
        // As a browser gives a body: chunks of up to 64 KiB.
        const val CHUNK_BYTES = 64 * 1024
    }
}
