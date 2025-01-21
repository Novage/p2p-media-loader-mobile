package com.novage.p2pml.server

import androidx.media3.common.util.UnstableApi
import com.novage.p2pml.logger.Logger
import com.novage.p2pml.parser.HlsManifestParser
import com.novage.p2pml.utils.P2PStateManager
import com.novage.p2pml.webview.WebViewManager
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@UnstableApi
internal class ServerModule(
    private val webViewManager: WebViewManager,
    private val manifestParser: HlsManifestParser,
    private val p2pEngineStateManager: P2PStateManager,
    private val customEngineImplementationPath: String? = null,
    private val onServerStarted: () -> Unit,
    private val onServerError: (String) -> Unit,
    private val onManifestChanged: suspend () -> Unit,
) {
    private var httpClient: OkHttpClient? = null
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(port: Int = 8080) {
        if (server != null) return

        try {
            server =
                embeddedServer(CIO, port) {
                    configureCORS(this)
                    configureRouting(this)
                    subscribeToServerStarted(this)
                }.start(wait = false)
        } catch (e: Exception) {
            val message = e.message ?: "Failed to start server on port $port"
            Logger.e(TAG, message, e)
            onServerError(message)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    suspend fun stop() {
        destroyHttpClient()
        stopServer()
    }

    private fun subscribeToServerStarted(application: Application) {
        application.monitor.subscribe(ApplicationStarted) {
            onServerStarted()
        }
    }

    private fun configureCORS(application: Application) {
        application.install(CORS) {
            anyHost()
        }
    }

    private fun configureRouting(application: Application) {
        httpClient = OkHttpClient()
        val manifestHandler = ManifestHandler(httpClient!!, manifestParser, webViewManager, onManifestChanged)
        val segmentHandler =
            SegmentHandler(
                httpClient!!,
                webViewManager,
                manifestParser,
                p2pEngineStateManager,
            )
        val routingModule =
            ServerRoutes(
                manifestHandler,
                segmentHandler,
                customEngineImplementationPath,
            )

        routingModule.setup(application)
    }

    private suspend fun destroyHttpClient() {
        withContext(Dispatchers.IO) {
            httpClient?.dispatcher?.executorService?.shutdown()
            httpClient?.connectionPool?.evictAll()
            httpClient = null
        }
    }

    companion object {
        private const val TAG = "ServerModule"
    }
}
