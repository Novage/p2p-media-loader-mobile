package com.novage.p2pml.logger

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
object Logger {
    private var isDebugEnabled = false

    fun setDebugEnabled(isDebugEnabled: Boolean) {
        this.isDebugEnabled = isDebugEnabled
    }

    fun d(
        tag: String,
        message: String,
    ) {
        if (isDebugEnabled) {
            Log.d(tag, message)
        }
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
