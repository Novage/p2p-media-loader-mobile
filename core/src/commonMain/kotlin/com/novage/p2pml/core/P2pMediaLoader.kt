package com.novage.p2pml.core

/** The library's entry point. */
public object P2pMediaLoader {
    // Not `const`: a constant is copied into each app that compiles against it.
    @Suppress("MayBeConstant") public val version: String = "0.1.0-dev"
}
