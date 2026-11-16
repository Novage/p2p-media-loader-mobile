package com.novage.p2pml.engine

/**
 * [JsEngine.evaluateScript] throws it when the engine is closed during the script or before it. The
 * script did not start, it was stopped, or it completed or failed before its result. It is not a
 * defect: a task lets it through, and the engine does not log it.
 */
internal class JsEngineClosedException(message: String) : Exception(message)
