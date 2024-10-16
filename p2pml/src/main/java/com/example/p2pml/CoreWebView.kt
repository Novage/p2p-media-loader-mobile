package com.example.p2pml

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class CoreWebView(
    context: Context,
    private val coroutineScope: CoroutineScope
) {
    private var playbackInfoCallback: () -> Pair<Float, Float> = { Pair(0f, 1f) }

    @SuppressLint("SetJavaScriptEnabled")
    private val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        webViewClient = WebViewClientCompat()
        visibility = View.GONE
    }
    private val webMessageProtocol = WebMessageProtocol(webView, coroutineScope)

    fun loadUrl(url: String) {
        coroutineScope.launch {
            webView.loadUrl(url)
        }
    }

    fun destroy() {
        webView.apply {
            parent?.let { (it as ViewGroup).removeView(this) }
            destroy()
        }
    }

    fun setUpPlaybackInfoCallback(callback: () -> Pair<Float, Float>) {
        this.playbackInfoCallback = callback
    }

    suspend fun requestSegmentBytes(segmentUrl: String): CompletableDeferred<ByteArray> {
        var currentPosition: Float
        var playbackSpeed: Float

        // ExoPlayer is not thread-safe, so we need to access it on the main thread
        withContext(Dispatchers.Main) {
            currentPosition = playbackInfoCallback().first
            playbackSpeed = playbackInfoCallback().second
        }

       return webMessageProtocol.requestSegmentBytes(segmentUrl, currentPosition, playbackSpeed)
    }

    fun sendInitialMessage() {
        webMessageProtocol.sendInitialMessage()
    }

    fun sendAllStreams(streamsJSON: String){
        coroutineScope.launch {
            webView.evaluateJavascript(
                "javascript:window.p2p.parseAllStreams('$streamsJSON');",
                null
            )
        }
    }

    fun sendStream(streamJSON: String){
        coroutineScope.launch {
            webView.evaluateJavascript(
                "javascript:window.p2p.parseStream('$streamJSON');",
                null
            )
        }
    }

    fun setManifestUrl(manifestUrl: String){
        coroutineScope.launch {
            webView.evaluateJavascript(
                "javascript:window.p2p.setManifestUrl('$manifestUrl');",
                null
            )
        }
    }
}
