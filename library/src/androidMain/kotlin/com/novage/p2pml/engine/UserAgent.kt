package com.novage.p2pml.engine

import android.os.Build
import com.novage.p2pml.P2pMediaLoader

/**
 * The library's `navigator.userAgent`, such as `P2PMediaLoaderMobile/0.1.0 (Android 12)`. The core
 * reads it for its default segment memory limit ("Android" without "Chrome": 1024 MiB), and its
 * logger adds `%c` colour codes for "AppleWebKit/" or "Firefox/", which this value does not have.
 * See specs/engine.md, "Globals".
 */
internal fun libraryUserAgent(release: String = Build.VERSION.RELEASE): String =
    "P2PMediaLoaderMobile/${P2pMediaLoader.version} (Android $release)"
