package com.novage.p2pml.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import com.novage.p2pml.SegmentRequest
import com.novage.p2pml.logger.Logger
import com.novage.p2pml.utils.SegmentAbortedException
import com.novage.p2pml.utils.SegmentNotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

internal class WebMessageProtocol(
    private val webView: WebView,
    private val coroutineScope: CoroutineScope,
) {
    @SuppressLint("RequiresFeature")
    private val channels: Array<WebMessagePortCompat> =
        WebViewCompat.createWebMessageChannel(webView)
    private val segmentResponseCallbacks = mutableMapOf<Int, CompletableDeferred<ByteArray>>()
    private val mutex = Mutex()
    private var incomingRequestId: Int? = null

    private var wasInitialMessageSent = false

    init {
        initializeWebMessageCallback()
    }

    @SuppressLint("RequiresFeature")
    private fun initializeWebMessageCallback() {
        channels[0].setWebMessageCallback(
            object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(
                    port: WebMessagePortCompat,
                    message: WebMessageCompat?,
                ) {
                    try {
                        when (message?.type) {
                            WebMessageCompat.TYPE_ARRAY_BUFFER -> {
                                handleSegmentIdBytes(message.arrayBuffer)
                            }

                            WebMessageCompat.TYPE_STRING -> {
                                handleMessage(message.data!!)
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error while handling message: ${e.message}", e)
                    }
                }
            },
        )
    }

    private fun handleSegmentIdBytes(arrayBuffer: ByteArray) {
        val requestId =
            incomingRequestId
                ?: throw IllegalStateException("Received segment bytes without a segment ID")

        coroutineScope.launch {
            val deferred =
                getSegmentResponseCallback(requestId) ?: throw IllegalStateException("No deferred found for request ID: $requestId")

            deferred.complete(arrayBuffer)
            removeSegmentResponseCallback(requestId)

            incomingRequestId = null
        }
    }

    private fun handleMessage(message: String) {
        if (message.contains("Error")) {
            handleErrorMessage(message)
        } else {
            handleSegmentIdMessage(message)
        }
    }

    private fun handleErrorMessage(message: String) {
        coroutineScope.launch {
            val error = message.substringBefore("|")
            val errorParts = error.split(":")
            val requestId = errorParts[1].toInt()
            val errorType = errorParts[2]
            val segmentId = message.substringAfter("|")

            val deferredSegmentBytes =
                getSegmentResponseCallback(requestId)
                    ?: throw IllegalStateException("No deferred found for request ID: $requestId")

            val exception =
                when (errorType) {
                    "aborted" -> SegmentAbortedException("$segmentId request was aborted")
                    "not_found" -> SegmentNotFoundException("$segmentId not found in core engine")
                    "failed" -> Exception("Error occurred while fetching segment")
                    else -> Exception("Unknown error occurred while fetching segment")
                }

            deferredSegmentBytes.completeExceptionally(exception)
            removeSegmentResponseCallback(requestId)
        }
    }

    private fun handleSegmentIdMessage(requestId: String) {
        val requestIdToInt =
            requestId.toIntOrNull() ?: throw IllegalStateException("Invalid request ID")
        incomingRequestId = requestIdToInt
    }

    @SuppressLint("RequiresFeature")
    suspend fun sendInitialMessage() {
        if (wasInitialMessageSent) return
        withContext(Dispatchers.Main) {
            val initialMessage = WebMessageCompat("", arrayOf(channels[1]))
            WebViewCompat.postWebMessage(
                webView,
                initialMessage,
                Uri.parse("*"),
            )
            wasInitialMessageSent = true
        }
    }

    suspend fun requestSegmentBytes(segmentUrl: String): CompletableDeferred<ByteArray> {
        val deferred = CompletableDeferred<ByteArray>()
        val requestId = generateRequestId()
        val segmentRequest = SegmentRequest(requestId, segmentUrl)
        val jsonRequest = Json.encodeToString(segmentRequest)

        addSegmentResponseCallback(requestId, deferred)
        sendSegmentRequest(jsonRequest)

        return deferred
    }

    private fun generateRequestId(): Int = Random.nextInt(0, 10000)

    private suspend fun sendSegmentRequest(segmentUrl: String) {
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                "javascript:window.p2p.processSegmentRequest('$segmentUrl');",
                null,
            )
        }
    }

    private suspend fun addSegmentResponseCallback(
        requestId: Int,
        deferred: CompletableDeferred<ByteArray>,
    ) {
        mutex.withLock {
            segmentResponseCallbacks[requestId] = deferred
        }
    }

    private suspend fun getSegmentResponseCallback(requestId: Int): CompletableDeferred<ByteArray>? =
        mutex.withLock {
            segmentResponseCallbacks[requestId]
        }

    private suspend fun removeSegmentResponseCallback(requestId: Int) {
        mutex.withLock {
            segmentResponseCallbacks.remove(requestId)
        }
    }

    private suspend fun resetSegmentResponseCallbacks() {
        mutex.withLock {
            segmentResponseCallbacks.forEach { (_, deferred) ->
                if (deferred.isCompleted) return@forEach

                deferred.completeExceptionally(
                    Exception("WebMessageProtocol is closing, no segment data will arrive."),
                )
            }
            segmentResponseCallbacks.clear()
        }
    }

    @SuppressLint("RequiresFeature")
    suspend fun clear() {
        resetSegmentResponseCallbacks()
    }

    companion object {
        private const val TAG = "WebMessageProtocol"
    }
}
