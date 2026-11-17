package com.novage.p2pml.engine

/**
 * The JNI functions of `native/src/jni.c` over `native/src/engine.h`. An engine is a handle that
 * [create] returns; [JsEngine] owns it and keeps the rules of engine.h.
 */
internal object NativeEngine {
    init {
        System.loadLibrary("p2pml")
    }

    /**
     * [userAgent] is `navigator.userAgent`, UTF-8. Throws [OutOfMemoryError] when the engine cannot
     * be created.
     */
    @JvmStatic external fun create(userAgent: ByteArray): Long

    /**
     * Blocks until the engine is closed, and writes the engine's lines to [log] meanwhile; the
     * requests of `fetch` go to [http]. False if the runtime or its globals could not be created,
     * and no close came first.
     */
    @JvmStatic external fun run(engine: Long, log: LogSink, http: HttpSink): Boolean

    /** False once the engine is closed, or when out of memory. */
    @JvmStatic external fun post(engine: Long, task: Runnable): Boolean

    @JvmStatic external fun close(engine: Long)

    @JvmStatic external fun free(engine: Long)

    /**
     * Engine thread only. [code] is UTF-8. Throws [JsException] for a JS exception,
     * [JsEngineClosedException] once the engine is closed, and [OutOfMemoryError].
     */
    @JvmStatic external fun evaluateScript(engine: Long, code: ByteArray, fileName: String): String

    /**
     * The events of request [id] of `fetch`, from any thread, while [engine] is not freed. False
     * once the engine is closed, or when out of memory. [headers] are names and values, in turn;
     * [bytes] up to [length] are copied.
     */
    // Its parameters are those of p2pml_http_response (native/src/http.h).
    @Suppress("LongParameterList")
    @JvmStatic
    external fun httpResponse(
        engine: Long,
        id: Int,
        status: Int,
        statusText: String,
        url: String,
        headers: Array<String>,
    ): Boolean

    @JvmStatic external fun httpData(engine: Long, id: Int, bytes: ByteArray, length: Int): Boolean

    @JvmStatic external fun httpEnd(engine: Long, id: Int): Boolean

    @JvmStatic external fun httpFail(engine: Long, id: Int, message: String): Boolean

    /** Takes the engine's lines on the engine thread. [level] is a [JsLog.Level]'s ordinal. */
    fun interface LogSink {
        fun write(level: Int, message: String)
    }

    /** The platform's HTTP client, on the engine thread (native/src/http.h). */
    interface HttpSink {
        /** [headers] are names and values, in turn. */
        fun start(id: Int, method: String, url: String, headers: Array<String>)

        fun cancel(id: Int)
    }
}
