package com.novage.p2pml.engine

/**
 * The JNI functions of `native/src/jni.c` over `native/src/engine.h`. An engine is a handle that
 * [create] returns; [JsEngine] owns it and keeps the rules of engine.h.
 */
internal object NativeEngine {
    init {
        System.loadLibrary("p2pml")
    }

    /** Throws [OutOfMemoryError] when the engine cannot be created. */
    @JvmStatic external fun create(): Long

    /** Blocks until the engine is closed. False if the runtime could not be created. */
    @JvmStatic external fun run(engine: Long): Boolean

    /** False once the engine is closed, or when out of memory. */
    @JvmStatic external fun post(engine: Long, task: Runnable): Boolean

    @JvmStatic external fun close(engine: Long)

    @JvmStatic external fun free(engine: Long)

    /**
     * Engine thread only. [code] is UTF-8. Throws [JsException] for a JS exception,
     * [JsEngineClosedException] once the engine is closed, and [OutOfMemoryError].
     */
    @JvmStatic external fun evaluateScript(engine: Long, code: ByteArray, fileName: String): String
}
