package com.novage.p2pml

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.novage.p2pml.Constants.CORE_FILE_URL
import com.novage.p2pml.Constants.CUSTOM_FILE_URL
import com.novage.p2pml.Constants.QueryParams.MANIFEST
import com.novage.p2pml.interop.EventListener
import com.novage.p2pml.interop.OnP2PReadyCallback
import com.novage.p2pml.interop.OnP2PReadyErrorCallback
import com.novage.p2pml.logger.Logger
import com.novage.p2pml.parser.HlsManifestParser
import com.novage.p2pml.providers.ExoPlayerPlaybackProvider
import com.novage.p2pml.providers.ExternalPlaybackProvider
import com.novage.p2pml.providers.PlaybackProvider
import com.novage.p2pml.server.ServerModule
import com.novage.p2pml.utils.EventEmitter
import com.novage.p2pml.utils.P2PStateManager
import com.novage.p2pml.utils.Utils
import com.novage.p2pml.webview.WebViewManager
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * `P2PMediaLoader` facilitates peer-to-peer media streaming within an Android application.
 *
 * @param onP2PReadyCallback Callback invoked when the P2P engine is ready for use
 * @param onP2PReadyErrorCallback Callback invoked when an error occurs
 * @param coreConfigJson Sets core P2P configurations. See [P2PML Core Config](https://novage.github.io/p2p-media-loader/docs/v2.1.0/types/p2p-media-loader-core.CoreConfig.html)
 * JSON string with core configurations. Default: empty string (uses default config)
 *
 * @param serverPort Port number for the local server. Default: 8080
 * @param customJavaScriptInterfaces List of custom JavaScript interfaces to inject into the WebView.
 * The feature has to be used with custom engine implementation. Default: empty list
 *
 * @param customEngineImplementationPath Resource path for custom implementation.
 * Default: null (uses built-in implementation)
 */
@UnstableApi
class P2PMediaLoader(
    private val onP2PReadyCallback: OnP2PReadyCallback,
    private val onP2PReadyErrorCallback: OnP2PReadyErrorCallback,
    private val coreConfigJson: String = "",
    private val serverPort: Int = Constants.DEFAULT_SERVER_PORT,
    private val customJavaScriptInterfaces: List<Pair<String, Any>> = emptyList(),
    private val customEngineImplementationPath: String? = null,
    enableDebugLogs: Boolean = false,
) {
    init {
        Logger.setDebugMode(enableDebugLogs)
    }

    // Second constructor for Java compatibility
    constructor(
        onP2PReadyCallback: OnP2PReadyCallback,
        onP2PReadyErrorCallback: OnP2PReadyErrorCallback,
        serverPort: Int,
        coreConfigJson: String,
        enableDebugLogs: Boolean,
    ) : this(
        onP2PReadyCallback,
        onP2PReadyErrorCallback,
        coreConfigJson,
        serverPort,
        enableDebugLogs = enableDebugLogs,
    )

    private val eventEmitter = EventEmitter()
    private val engineStateManager = P2PStateManager()
    private var appState = AppState.INITIALIZED

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var serverModule: ServerModule? = null
    private var manifestParser: HlsManifestParser? = null
    private var webViewManager: WebViewManager? = null
    private var playbackProvider: PlaybackProvider? = null

    /**
     * Adds an event listener to the P2P engine.
     *
     * @param event Event type to listen for
     * @param listener Callback function to invoke when the event occurs
     */
    fun <T> addEventListener(
        event: CoreEventMap<T>,
        listener: EventListener<T>,
    ) {
        eventEmitter.addEventListener(event, listener)
    }

    /**
     * Removes an event listener from the P2P engine.
     *
     * @param event Event type to remove the listener from
     * @param listener Callback function to remove
     */
    fun <T> removeEventListener(
        event: CoreEventMap<T>,
        listener: EventListener<T>,
    ) {
        eventEmitter.removeEventListener(event, listener)
    }

    /**
     * Initializes and starts P2P media streaming components.
     *
     * @param context Android context required for WebView initialization
     * @param exoPlayer ExoPlayer instance for media playback
     * @throws IllegalStateException if called in an invalid state
     */
    fun start(
        context: Context,
        exoPlayer: ExoPlayer,
    ) {
        Logger.d(TAG, "Starting P2P Media Loader with ExoPlayer")
        prepareStart(context, ExoPlayerPlaybackProvider(exoPlayer))
    }

    /**
     * Initializes and starts P2P media streaming components.
     *
     * @param context Android context required for WebView initialization
     * @param getPlaybackInfo Function to retrieve playback information
     * @throws IllegalStateException if called in an invalid state
     */
    fun start(
        context: Context,
        getPlaybackInfo: () -> PlaybackInfo,
    ) {
        Logger.d(TAG, "Starting P2P Media Loader with playback info callback")
        prepareStart(context, ExternalPlaybackProvider(getPlaybackInfo))
    }

    private fun prepareStart(
        context: Context,
        provider: PlaybackProvider,
    ) {
        if (appState == AppState.STARTED) {
            val errorMessage = "Cannot start P2PMediaLoader in state: $appState"
            Logger.e(TAG, errorMessage)
            throw IllegalStateException(errorMessage)
        }

        job = Job()
        scope = CoroutineScope(job!! + Dispatchers.Main)
        playbackProvider = provider

        initializeComponents(context, provider)

        appState = AppState.STARTED
    }

    private fun initializeComponents(
        context: Context,
        playbackProvider: PlaybackProvider,
    ) {
        manifestParser = HlsManifestParser(playbackProvider, serverPort)
        webViewManager =
            WebViewManager(
                context,
                scope!!,
                engineStateManager,
                playbackProvider,
                eventEmitter,
                customJavaScriptInterfaces,
                onPageLoadFinished = { onWebViewLoaded() },
            )

        serverModule =
            ServerModule(
                webViewManager!!,
                manifestParser!!,
                engineStateManager,
                customEngineImplementationPath,
                onServerStarted = { onServerStarted() },
                onServerError = { onP2PReadyErrorCallback.onError(it) },
                onManifestChanged = { onManifestChanged() },
            ).apply { start(serverPort) }
    }

    /**
     * Applies dynamic core configurations to the `P2PMediaLoader` engine.
     *
     * @param dynamicCoreConfigJson A JSON string containing dynamic core configurations for the P2P engine.
     * Refer to the [DynamicCoreConfig Documentation](https://novage.github.io/p2p-media-loader/docs/v2.1.0/types/p2p-media-loader-core.DynamicCoreConfig.html).
     * @throws IllegalStateException if P2PMediaLoader is not started
     */
    fun applyDynamicConfig(dynamicCoreConfigJson: String) {
        ensureStarted()

        webViewManager!!.applyDynamicConfig(dynamicCoreConfigJson)
    }

    /**
     * Converts an external HLS manifest URL to a local URL handled by the P2P engine.
     *
     * @param manifestUrl External HLS manifest URL (.m3u8)
     * @return Local URL for P2P-enabled playback
     * @throws IllegalStateException if P2PMediaLoader is not started
     */
    fun getManifestUrl(manifestUrl: String): String {
        ensureStarted()

        val encodedManifestURL = manifestUrl.encodeURLParameter()
        return Utils.getUrl(serverPort, "$MANIFEST$encodedManifestURL")
    }

    private fun ensureStarted() {
        if (appState != AppState.STARTED) {
            val errorMessage = "Operation not allowed in state: $appState"
            Logger.e(TAG, errorMessage)
            throw IllegalStateException(errorMessage)
        }
    }

    /**
     * Stops P2P streaming and releases all resources.
     * Call [start] to reinitialize after stopping.
     *
     * @throws IllegalStateException if P2PMediaLoader is not started
     */
    fun stop() {
        ensureStarted()

        Logger.d(TAG, "Stopping P2PMediaLoader...")

        runBlocking {
            webViewManager?.destroy()
            webViewManager = null

            serverModule?.stop()
            serverModule = null

            manifestParser?.reset()
            manifestParser = null

            playbackProvider?.resetData()
            playbackProvider = null

            engineStateManager.reset()
            eventEmitter.removeAllListeners()

            appState = AppState.STOPPED

            job?.cancel()
            job = null
            scope = null
        }
        Logger.d(TAG, "P2PMediaLoader stopped and resources freed.")
    }

    private suspend fun onManifestChanged() {
        Logger.d(TAG, "Manifest changed, resetting data")
        playbackProvider!!.resetData()
        manifestParser!!.reset()
    }

    private fun onWebViewLoaded() {
        scope!!.launch {
            try {
                Logger.d(TAG, "WebView loaded, initializing P2P engine")
                webViewManager!!.initCoreEngine(coreConfigJson)
                Logger.d(TAG, "P2P engine initialized, notifying onP2PReadyCallback")
                onP2PReadyCallback.onReady()
            } catch (e: Exception) {
                onP2PReadyErrorCallback.onError(e.message ?: "Unknown error")
            }
        }
    }

    private fun onServerStarted() {
        Logger.d(TAG, "Server started on port $serverPort")
        val urlPath =
            if (customEngineImplementationPath != null) {
                Utils.getUrl(serverPort, CUSTOM_FILE_URL)
            } else {
                Utils.getUrl(serverPort, CORE_FILE_URL)
            }

        try {
            Logger.d(TAG, "Loading WebView with URL: $urlPath")
            webViewManager!!.loadWebView(urlPath)
        } catch (e: Exception) {
            onP2PReadyErrorCallback.onError(e.message ?: "Unknown error")
        }
    }

    companion object {
        private const val TAG = "P2PMediaLoader"
    }
}
