package com.example.p2pml.server

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.p2pml.Constants.MPEGURL_CONTENT_TYPE
import com.example.p2pml.utils.Utils
import com.example.p2pml.webview.WebViewManager
import com.example.p2pml.parser.HlsManifestParser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(UnstableApi::class)
internal class ManifestHandler(
    private val manifestParser: HlsManifestParser,
    private val webViewManager: WebViewManager
) {
    private var isMasterManifestProcessed = false
    private val mutex = Mutex()

    suspend fun handleManifestRequest(call: ApplicationCall) {
        val manifestParam = call.request.queryParameters["manifest"]
            ?: return call.respondText(
                "Missing 'manifest' parameter",
                status = HttpStatusCode.BadRequest
            )
        val decodedManifestUrl = manifestParam.decodeURLQueryComponent()

        try {
            val modifiedManifest = manifestParser.getModifiedManifest(call, decodedManifestUrl)
            val needsInitialSetup = checkAndSetInitialProcessing()

            handleUpdate(decodedManifestUrl, needsInitialSetup)
            call.respondText(modifiedManifest, ContentType.parse(MPEGURL_CONTENT_TYPE))
        } catch (e: Exception) {
            call.respondText(
                "Error fetching master manifest",
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    private suspend fun handleUpdate(manifestUrl: String, needsInitialSetup: Boolean) {
        val updateStreamJSON = manifestParser.getUpdateStreamParamsJSON(manifestUrl)

        if (needsInitialSetup) {
            val streamsJSON = manifestParser.getStreamsJSON()
            Utils.runOnUiThread {
                webViewManager.sendInitialMessage()
                webViewManager.setManifestUrl(manifestUrl)
                webViewManager.sendAllStreams(streamsJSON)
                updateStreamJSON?.let { webViewManager.sendStream(it) }
            }
        } else {
            updateStreamJSON?.let { json ->
                Utils.runOnUiThread { webViewManager.sendStream(json) }
            } ?: throw IOException("updateStreamJSON is null")
        }
    }

    private suspend fun checkAndSetInitialProcessing(): Boolean = mutex.withLock {
        if (isMasterManifestProcessed) return false

        isMasterManifestProcessed = true
        return true
    }



}