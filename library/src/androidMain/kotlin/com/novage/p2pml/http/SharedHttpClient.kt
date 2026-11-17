package com.novage.p2pml.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher

/**
 * The HTTP client of every engine's `fetch`: one per process, so that the engines share its
 * connections. See specs/engine.md, "The platform's network clients".
 */
internal object SharedHttpClient {
    // Up to 6 requests at once to one host, as a browser; OkHttp counts a request until its
    // headers arrive.
    private const val MAX_REQUESTS_PER_HOST = 6
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    // Far above the core's default timeouts (3 s with no bytes, 10 s to the first byte): it ends a
    // read that stalled after the core gave up. A cancel does not stop a blocked read, so without
    // it
    // such a read holds its thread and socket for good. A core timeout set above it is cut to it.
    private const val READ_TIMEOUT_SECONDS = 30L

    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            // A response of any status is a response, as fetch gives it.
            expectSuccess = false
            followRedirects = true
            engine {
                config {
                    connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    // The core has its own timeouts, and aborts a request itself.
                    writeTimeout(0, TimeUnit.SECONDS)
                    callTimeout(0, TimeUnit.SECONDS)
                    dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
                }
            }
        }
    }
}
