package com.novage.p2pml.server

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.novage.p2pml.logger.Logger
import com.novage.p2pml.parser.HlsManifestParser
import com.novage.p2pml.utils.P2PStateManager
import com.novage.p2pml.utils.SegmentAbortedException
import com.novage.p2pml.utils.SegmentNotFoundException
import com.novage.p2pml.utils.Utils
import com.novage.p2pml.webview.WebViewManager
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@OptIn(UnstableApi::class)
internal class SegmentHandler(
    private val httpClient: OkHttpClient,
    private val webViewManager: WebViewManager,
    private val parser: HlsManifestParser,
    private val p2pEngineStateManager: P2PStateManager,
) {
    suspend fun handleSegmentRequest(call: ApplicationCall) {
        val segmentUrlParam =
            call.request.queryParameters["segment"]
                ?: return call.respondText(
                    "Missing 'segment' parameter",
                    status = HttpStatusCode.BadRequest,
                )
        val decodedSegmentUrl = Utils.decodeBase64Url(segmentUrlParam)
        val byteRange = call.request.headers[HttpHeaders.Range]

        Logger.d(TAG, "Handling segment request for $decodedSegmentUrl")

        try {
            val isCurrentSegment = parser.isCurrentSegment(decodedSegmentUrl)
            if (p2pEngineStateManager.isEngineDisabled() || !isCurrentSegment) {
                Logger.w(
                    TAG,
                    "P2P engine is disabled or segment is not current, falling back to direct fetch for $decodedSegmentUrl.",
                )
                fetchAndRespondWithSegment(call, decodedSegmentUrl, byteRange)
                return
            }

            val deferredSegmentBytes =
                webViewManager.requestSegmentBytes(decodedSegmentUrl)
                    ?: throw IllegalStateException("P2P engine is disabled")
            val segmentBytes = deferredSegmentBytes.await()

            Logger.d(TAG, "Segment bytes received for $decodedSegmentUrl")
            respondSegment(call, segmentBytes, byteRange != null)
        } catch (e: SegmentAbortedException) {
            val message = "Segment $decodedSegmentUrl | ${e.message}"

            Logger.w(TAG, message)
            call.respondText(message, status = HttpStatusCode.RequestTimeout)
        } catch (e: SegmentNotFoundException) {
            Logger.e(TAG, "Segment $decodedSegmentUrl not found: ${e.message}, falling back to direct fetch.")
            fetchAndRespondWithSegment(call, decodedSegmentUrl, byteRange)
        } catch (e: Exception) {
            Logger.e(TAG, "Error handling segment $decodedSegmentUrl request: ${e.message}, falling back to direct fetch.", e)
            fetchAndRespondWithSegment(call, decodedSegmentUrl, byteRange)
        }
    }

    private suspend fun respondSegment(
        call: ApplicationCall,
        segmentBytes: ByteArray,
        isPartial: Boolean,
    ) {
        if (isPartial) {
            call.respond(
                object : OutgoingContent.ByteArrayContent() {
                    override val contentType: ContentType = ContentType.Application.OctetStream
                    override val contentLength: Long = segmentBytes.size.toLong()
                    override val status: HttpStatusCode = HttpStatusCode.PartialContent

                    override fun bytes(): ByteArray = segmentBytes
                },
            )
        } else {
            call.respondBytes(segmentBytes, ContentType.Application.OctetStream)
        }
    }

    private suspend fun fetchAndRespondWithSegment(
        call: ApplicationCall,
        url: String,
        byteRange: String? = null,
    ) = withContext(Dispatchers.IO) {
        val filteredUrl = url.substringBeforeLast("|")
        val request =
            Request
                .Builder()
                .url(filteredUrl)
                .apply { Utils.copyHeaders(call, this) }
                .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = "Failed to direct fetch segment: ${response.code}"

                Log.e(TAG, message)
                call.respondText(message, status = HttpStatusCode.fromValue(response.code))
                return@withContext
            }

            val segmentBytes =
                response.body?.use { it.bytes() }
                    ?: run {
                        val message = "Failed to read segment response body. URL: $filteredUrl"

                        Logger.e(TAG, message)
                        throw IllegalStateException(message)
                    }

            respondSegment(call, segmentBytes, byteRange != null)
        }
    }

    companion object {
        private const val TAG = "SegmentHandler"
    }
}
