package com.novage.p2pml.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONException

/**
 * The listeners of a [Core]'s events, and their thread. [subscribe] tells the bridge to forward an
 * event (true) or to stop (false): at the first listener of the event, and after the last. The
 * listeners run in order on one thread of their own (`p2pml-events`), never on the engine thread,
 * so that they can call the core and take their time; [warn] reports a listener that throws.
 */
internal class EventHub(
    private val subscribe: (name: String, listen: Boolean) -> Unit,
    private val warn: (String) -> Unit,
) {
    // An event and its listeners, by the event's JS name.
    private class Listeners(val event: CoreEvent<*>) : CopyOnWriteArrayList<CoreEventListener<*>>()

    private val listeners = ConcurrentHashMap<String, Listeners>()
    // Guards the counts against subscribe.
    private val lock = Any()
    // One thread, which starts at the first event.
    private val thread =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { task ->
            Thread(task, THREAD_NAME).apply { isDaemon = true }
        }

    fun <T> add(event: CoreEvent<T>, listener: CoreEventListener<T>) {
        synchronized(lock) {
            val list = listeners.getOrPut(event.name) { Listeners(event) }
            list += listener
            if (list.size == 1) subscribe(event.name, true)
        }
    }

    fun <T> remove(event: CoreEvent<T>, listener: CoreEventListener<T>) {
        synchronized(lock) {
            val list = listeners[event.name] ?: return
            if (list.remove(listener) && list.isEmpty()) subscribe(event.name, false)
        }
    }

    /** Event [name] with its [json] details, from the engine thread: delivered later, in order. */
    fun post(name: String, json: String) {
        try {
            thread.execute { deliver(name, json) }
        } catch (_: RejectedExecutionException) {
            // Closed: the event goes with the core.
        }
    }

    // On the event thread: to each listener of the event at this moment.
    private fun deliver(name: String, json: String) {
        val list = listeners[name]
        if (list.isNullOrEmpty()) return
        val details = detailsOf(list.event, json) ?: return
        for (listener in list) {
            try {
                // Safe: a list holds only listeners of its event, whose parse gives their type.
                @Suppress("UNCHECKED_CAST") (listener as CoreEventListener<Any?>).onEvent(details)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // As the JS core, which swallows any throw: one listener's failure does not stop
                // the others, and does not end the thread, which would end the app.
                // The text of an app's exception can throw too: then its class and its frames.
                val text = runCatching {
                    e.stackTraceToString()
                }
                    .getOrElse {
                        e.javaClass.name +
                            e.stackTrace.joinToString("") { frame -> "\n\tat $frame" }
                    }
                warn("A listener of $name threw: $text")
            }
        }
    }

    private fun detailsOf(event: CoreEvent<*>, json: String): Any? =
        try {
            event.detailsOf(json)
        } catch (e: JSONException) {
            warn("The details of ${event.name} could not be read: $e")
            null
        }

    /**
     * Ends the thread: the events that it has not delivered go. A listener that runs now ends as
     * usual: no interrupt, which would fail its own work.
     */
    fun close() {
        thread.shutdown()
        thread.queue.clear()
    }

    private companion object {
        const val THREAD_NAME = "p2pml-events"
    }
}
