# P2P Media Loader Mobile

![Platform: Android](https://img.shields.io/badge/platform-Android-blue) 
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.20-blue.svg?logo=kotlin)](https://kotlinlang.org) 
[![JitPack](https://jitpack.io/v/Novage/p2p-media-loader-mobile.svg)](https://jitpack.io/#Novage/p2p-media-loader-mobile)
[![Build Status](https://img.shields.io/github/actions/workflow/status/Novage/p2p-media-loader-mobile/check-build.yml)](https://github.com/Novage/p2p-media-loader-mobile/actions) 

A Kotlin library for peer-to-peer media loading, designed to enhance streaming performance and reduce server load. Seamlessly integrate P2PML into your Android application with minimal setup.

## Features
- Efficient P2P content sharing for HLS
- Simple integration and configuration
- Improved streaming performance with reduced server bandwidth usage

---

## Getting Started

### Step 1: Add JitPack to Your Project

To include the P2P Media Loader Mobile library, first, configure `dependencyResolutionManagement` in your **`settings.gradle`** file:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```

### Step 2: Add the Library Dependency

Add the following implementation line to your **`build.gradle`** (app module):

```kotlin
implementation("com.github.Novage:p2p-media-loader-mobile:main-SNAPSHOT")
```

### Step 3: Configure the AndroidManifest

Ensure that your app has the necessary permissions and configurations by updating the **`AndroidManifest.xml`** file:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Add internet access permission -->
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:networkSecurityConfig="@xml/network_security_config"
        ... >
        ...
    </application>
</manifest>
```

### Step 4: Allow Localhost Connections

Create or update the **`res/xml/network_security_config.xml`** file to allow localhost connections:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

Make sure to reference this file in the `<application>` tag of your **`AndroidManifest.xml`**.

---

## Usage

#### ⚠️ P2P (WebRTC) may not connect to the outer world if it runs on Android emulators due to its virtual machine [network configuration](https://developer.android.com/studio/run/emulator-networking) (NAT). Please test P2P connectivity on real devices. ⚠️

Once you've completed the setup, P2P Media Loader is ready to use in your application!

### Kotlin Example

```kotlin
class MainActivity : ComponentActivity() {
    companion object {
        // URL to the media manifest
        private const val MANIFEST_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        
        // JSON configuration for P2P Media Loader
        // See https://novage.github.io/p2p-media-loader/docs/v2.1.0/types/p2p-media-loader-core.CoreConfig.html
        private const val CORE_CONFIG_JSON = "{\"swarmId\":\"TEST_KOTLIN\"}"
        
        // Port on which the P2P server will run
        private const val SERVER_PORT = 8081
    }

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var p2pml: P2PMediaLoader

    // State variables to manage UI state
    private val isLoading = mutableStateOf(true)
    private val videoTitle = mutableStateOf("Loading Video...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize ExoPlayer
        exoPlayer = ExoPlayer.Builder(this).build()
        
        // Initialize P2P Media Loader with callbacks
        p2pml = P2PMediaLoader(
            onP2PReadyCallback = { initializePlayback() },
            onP2PReadyErrorCallback = { onP2PReadyErrorCallback(it) },
            coreConfigJson = CORE_CONFIG_JSON,
            serverPort = SERVER_PORT,
        )
        
        // Event listeners for P2P Media Loader
        p2pml.addEventListener(CoreEventMap.OnPeerConnect) { params ->
            // Implement logic to handle peer connection
            Log.d("P2PML", "Peer connected: ${params.peerId} - ${params.streamType}")
        }

        p2pml.addEventListener(CoreEventMap.OnSegmentLoaded) { params ->
            // Implement logic to handle loaded segment
            Log.d("P2PML", "Segment loaded: ${params.segmentUrl} - ${params.bytesLength} - ${params.downloadSource}")
        }

        p2pml.addEventListener(CoreEventMap.OnChunkDownloaded) { params ->
            // Implement logic to handle downloaded chunk
            Log.d("P2PML", "Chunk downloaded: ${params.bytesLength} - ${params.downloadSource} - ${params.downloadSource}")
        }
        
        // Start P2P Media Loader with the activity context and ExoPlayer instance
        p2pml.start(this, exoPlayer)

        // Listener to update UI based on playback state
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isLoading.value = false
                    videoTitle.value = "Big Buck Bunny"
                }
            }
        })

        // Set the Compose UI content
        setContent {
            ExoPlayerScreen(
                player = exoPlayer,
                videoTitle = videoTitle.value,
                isLoading = isLoading.value,
            )
        }
    }

    private fun initializePlayback() {
        val manifest = p2pml.getManifestUrl(MANIFEST_URL)

        val httpDataSource = DefaultHttpDataSource.Factory()
            .setReadTimeoutMs(15000) // Read timeout
            .setConnectTimeoutMs(15000) // Connect timeout

        val mediaItem = MediaItem.fromUri(manifest)
        val mediaSource = HlsMediaSource.Factory(httpDataSource)
            .createMediaSource(mediaItem)

        exoPlayer.apply {
            playWhenReady = true
            setMediaSource(mediaSource)
            prepare()
        }
    }

    private fun onP2PReadyErrorCallback(error: String) {
        // Implement error handling logic here
    }

    override fun onStop() {
        super.onStop()
        // Disable P2P features when the activity stops
        // See https://novage.github.io/p2p-media-loader/docs/v2.1.0/types/p2p-media-loader-core.DynamicCoreConfig.html
        p2pml.applyDynamicConfig("{\"isP2PDisabled\": true}")
    }

    override fun onRestart() {
        super.onRestart()
        // Re-enable P2P features when the activity restarts
        // See https://novage.github.io/p2p-media-loader/docs/v2.1.0/types/p2p-media-loader-core.DynamicCoreConfig.html
        p2pml.applyDynamicConfig("{\"isP2PDisabled\": false}")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release ExoPlayer resources and stop P2P Media Loader
        exoPlayer.release()
        p2pml.stop()
    }
}
```

### Java Example

```Java
public class MainActivity extends AppCompatActivity {
    // URL to the media manifest
    private static final String MANIFEST_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";

    // Port on which the P2P server will run
    private static final int SERVER_PORT = 8081;

    // JSON configuration for P2P Media Loader
    // See https://novage.github.io/p2p-media-loader/docs/v2.1.0/types/p2p-media-loader-core.CoreConfig.html
    private static final String CORE_CONFIG_JSON = "{\"swarmId\":\"TEST_KOTLIN\"}";

    private ExoPlayer exoPlayer;
    private P2PMediaLoader p2pml;

    private PlayerView playerView;
    private ProgressBar loadingIndicator;
    private TextView videoTitle;

    private void initializePlayback() {
        String manifestUrl = p2pml.getManifestUrl(MANIFEST_URL);

        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15000) // Set the connection timeout
                .setReadTimeoutMs(15000); // Set the read timeout

        MediaItem mediaItem = MediaItem.fromUri(manifestUrl);
        HlsMediaSource mediaSource = new HlsMediaSource
                .Factory(dataSourceFactory)
                .createMediaSource(mediaItem);


        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);

        playerView.setPlayer(exoPlayer);
    }

    private void onP2PReadyErrorCallback(String error) {
       // Implement error handling logic here
    }
    
    private void initializeUI() {
        playerView = findViewById(R.id.playerView);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        videoTitle = findViewById(R.id.videoTitle);

        loadingIndicator.setVisibility(View.VISIBLE);
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBarsInsets.left, systemBarsInsets.top,
                    systemBarsInsets.right, systemBarsInsets.bottom);
            return insets;
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Initialize ExoPlayer
        exoPlayer = new ExoPlayer.Builder(this).build();

        // Initialize P2P Media Loader with callbacks
        p2pml = new P2PMediaLoader(
            this::initializePlayback,
            this::onP2PReadyErrorCallback,
            SERVER_PORT,
            CORE_CONFIG_JSON
        );

        // Event listeners for P2P Media Loader
        p2pml.addEventListener(CoreEventMap.OnPeerConnect, (params) -> {
            // Implement logic to handle peer connection
            System.out.println("Peer connected: " + params.getPeerId() + " - " + params.getStreamType());
        });

        p2pml.addEventListener(CoreEventMap.OnSegmentLoaded, (params) -> {
            // Implement logic to handle loaded segment
            System.out.println("Segment loaded: " + params.getSegmentUrl() + " - " + params.getBytesLength() + " - " + params.getDownloadSource());
        });

        p2pml.addEventListener(CoreEventMap.OnChunkDownloaded, (params) -> {
            // Implement logic to handle downloaded chunk
            System.out.println("Chunk downloaded: " + params.getBytesLength() + " - " + params.getDownloadSource() + " - " + params.getDownloadSource());
        });
        
        // Start P2P Media Loader with the activity context and ExoPlayer instance
        p2pml.start(this, exoPlayer);

        // Listener to update UI based on playback state
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState != Player.STATE_READY) return;

                loadingIndicator.setVisibility(View.GONE);
                videoTitle.setText("Big Buck Bunny");
            }
        });


        initializeUI();
        applyWindowInsets();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Disable P2P features when the activity stops
        if (p2pml != null) {
            // See https://novage.github.io/p2p-media-loader/docs/v2.1.0/types/p2p-media-loader-core.DynamicCoreConfig.html
            p2pml.applyDynamicConfig("{ \"isP2PDisabled\": false }");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Re-enable P2P features when the activity restarts
        if (p2pml != null) {
            // See https://novage.github.io/p2p-media-loader/docs/v2.1.0/types/p2p-media-loader-core.DynamicCoreConfig.html
            p2pml.applyDynamicConfig("{ \"isP2PDisabled\": true }");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Release ExoPlayer resources and stop P2P Media Loader
        if (p2pml != null) {
            p2pml.stop();
        }
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }
}
```
