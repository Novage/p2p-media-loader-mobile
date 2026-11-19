package com.novage.p2pml.engine

/**
 * A JS exception that reached Kotlin. [message] is the exception converted to a string, such as
 * `TypeError: x is not a function`; [jsStack] is its `stack`, or null when it has none.
 */
internal class JsException(message: String, val jsStack: String?) : Exception(message) {
    // With the JS stack, so that a logged stack trace shows where in JS it failed.
    override fun toString(): String =
        if (jsStack == null) super.toString() else "${super.toString()}\n$jsStack"
}
