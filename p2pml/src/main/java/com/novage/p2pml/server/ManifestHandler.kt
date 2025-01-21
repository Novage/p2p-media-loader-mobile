package com.novage.p2pml.server

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.novage.p2pml.Constants.MPEGURL_CONTENT_TYPE
import com.novage.p2pml.parser.HlsManifestParser
import com.novage.p2pml.utils.Utils
import com.novage.p2pml.webview.WebViewManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class ManifestFetchResult(
    val manifestContent: String,
    val responseUrl: String,
)

@OptIn(UnstableApi::class)
internal class ManifestHandler(
    private val httpClient: OkHttpClient,
    private val manifestParser: HlsManifestParser,
    private val webViewManager: WebViewManager,
    private val onManifestChanged: suspend () -> Unit,
) {
    private var isInitialManifestProcessed = false
    private val mutex = Mutex()

    suspend fun handleManifestRequest(call: ApplicationCall) {
        val manifestParam =
            call.request.queryParameters["manifest"]
                ?: return call.respondText(
                    "Missing 'manifest' parameter",
                    status = HttpStatusCode.BadRequest,
                )
        val decodedManifestUrl = manifestParam.decodeURLQueryComponent()

        try {
            val fetchResult = fetchManifest(call, decodedManifestUrl)
            val doesManifestExist = manifestParser.doesManifestExist(decodedManifestUrl)

            if (!doesManifestExist) {
                reset()
                onManifestChanged()
            }

            val modifiedManifest =
                manifestParser.getModifiedManifest(
                    fetchResult.manifestContent,
                    fetchResult.responseUrl,
                )
            val needsInitialSetup = checkAndSetInitialProcessing()

            handleUpdate(decodedManifestUrl, needsInitialSetup)
            call.respondText(modifiedManifest, ContentType.parse(MPEGURL_CONTENT_TYPE))
        } catch (e: Exception) {
            val message = "Failed to process manifest request: ${e.message}"

            Log.e(TAG, message)
            call.respondText(message, status = HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun handleUpdate(
        manifestUrl: String,
        needsInitialSetup: Boolean,
    ) {
        try {
            val updateStreamJson = manifestParser.getUpdateStreamParamsJson(manifestUrl)

            if (needsInitialSetup) {
                val streamsJson = manifestParser.getStreamsJson()

                webViewManager.sendInitialMessage()
                webViewManager.setManifestUrl(manifestUrl)
                webViewManager.sendAllStreams(streamsJson)

                updateStreamJson?.let { webViewManager.sendStream(it) }
            } else {
                updateStreamJson?.let { json ->
                    webViewManager.sendStream(json)
                } ?: throw Exception("updateStreamJson is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error occurred: ${e.message}")
        }
    }

    private suspend fun checkAndSetInitialProcessing(): Boolean =
        mutex.withLock {
            if (isInitialManifestProcessed) return false

            isInitialManifestProcessed = true
            return true
        }

    private suspend fun fetchManifest(
        call: ApplicationCall,
        manifestUrl: String,
    ): ManifestFetchResult =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(manifestUrl)
                    .apply { Utils.copyHeaders(call, this) }
                    .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Failed to fetch manifest: $manifestUrl Response code: ${response.code}",
                    )
                }

                val body =
                    response.body?.string()
                        ?: throw IllegalStateException("Empty response body for manifest: $manifestUrl")
                val responseUrl = response.request.url.toString()

                ManifestFetchResult(body, responseUrl)
            }
        }

    private suspend fun reset() {
        mutex.withLock {
            isInitialManifestProcessed = false
        }
    }

    companion object {
        private const val TAG = "ManifestHandler"
    }
}
