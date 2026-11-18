package com.novage.p2pml.http

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

// A WebSocket server for the tests: it echoes messages and answers a close, as a tracker does.
internal open class EchoListener : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) {
        webSocket.send(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        webSocket.send(bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }
}
