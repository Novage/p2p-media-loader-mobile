package com.novage.p2pml.engine

import android.util.Log

/** Where the engine writes its lines: console, and the report of uncaught JS errors. */
internal fun interface JsLog {
    fun write(level: Level, message: String)

    // The order of p2pml_log_level in native/src/engine.h.
    enum class Level(internal val priority: Int) {
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
    }

    companion object {
        private const val TAG = "P2PML"

        val logcat: JsLog = JsLog { level, message -> Log.println(level.priority, TAG, message) }
    }
}
