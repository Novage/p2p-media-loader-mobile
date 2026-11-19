package com.novage.p2pml.core

import kotlin.test.Test
import kotlin.test.assertTrue

class P2pMediaLoaderTest {
    @Test
    fun hasAVersion() {
        assertTrue(P2pMediaLoader.version.isNotBlank())
    }
}
