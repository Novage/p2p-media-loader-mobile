package com.novage.p2pml.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.novage.p2pml.core.LiveDelay
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The engine's load control, around a control that notes each call.
@OptIn(UnstableApi::class)
class CoreLoadControlTest {
    private val calls = mutableListOf<String>()
    private var wanted = true
    private val allocator = DefaultAllocator(true, ALLOCATION_BYTES)
    private val inner =
        Proxy.newProxyInstance(
            LoadControl::class.java.classLoader,
            arrayOf(LoadControl::class.java),
        ) { _, method, _ ->
            calls += method.name
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> wanted
                Long::class.javaPrimitiveType -> 0L
                Allocator::class.java -> allocator
                else -> null
            }
        } as LoadControl
    private var placement: LiveDelay? = null
    private val control = CoreLoadControl(inner) { placement }

    // In a live window that the engine places, unless [timeline] says otherwise.
    private fun buffered(seconds: Double, timeline: Timeline = liveTimeline()) =
        LoadControl.Parameters(
            PlayerId.UNSET,
            timeline,
            MediaPeriodId(timeline.getUidOfPeriod(0)),
            0,
            (seconds * C.MICROS_PER_SECOND).toLong(),
            1f,
            true,
            false,
            C.TIME_UNSET,
            C.TIME_UNSET,
        )

    @Test
    fun withNoPlacementTheInnerControlDecides() {
        assertTrue(control.shouldContinueLoading(buffered(LONG_BUFFER)))
        wanted = false
        assertFalse(control.shouldContinueLoading(buffered(0.0)))
    }

    @Test
    fun aPlacedPlayerLoadsUpToTheCoresBuffer() {
        placement = PLACEMENT
        assertTrue(control.shouldContinueLoading(buffered(PLACEMENT.playerBuffer - 0.1)))
        assertFalse(control.shouldContinueLoading(buffered(PLACEMENT.playerBuffer)), "at the rule")
        wanted = false
        assertFalse(control.shouldContinueLoading(buffered(0.0)), "the inner control can stop it")
        assertEquals(
            3,
            calls.count { it == "shouldContinueLoading" },
            "the inner control asked each time",
        )
    }

    // The limit is measured from the engine's delay: where the app's target offset keeps Media3's,
    // the buffer is the inner control's (rule 1 of the web library's player-adapters spec).
    @Test
    fun aWindowThatTheEngineDoesNotPlaceKeepsTheInnerBuffer() {
        placement = PLACEMENT
        val own = appOffsetItem()
        assertTrue(control.shouldContinueLoading(buffered(LONG_BUFFER, liveTimeline(own))))
        assertFalse(control.shouldContinueLoading(buffered(LONG_BUFFER)), "a placed window")
        val unknown =
            LoadControl.Parameters(
                PlayerId.UNSET,
                liveTimeline(),
                MediaPeriodId(Any()),
                0,
                (LONG_BUFFER * C.MICROS_PER_SECOND).toLong(),
                1f,
                true,
                false,
                C.TIME_UNSET,
                C.TIME_UNSET,
            )
        assertTrue(
            control.shouldContinueLoading(unknown),
            "a period that the timeline does not know",
        )
    }

    @Test
    fun everyCallReachesTheInnerControl() {
        val parameters = buffered(0.0)
        control.onPrepared(PlayerId.UNSET)
        control.onTracksSelected(parameters, TrackGroupArray.EMPTY, emptyArray())
        control.onStopped(PlayerId.UNSET)
        control.onReleased(PlayerId.UNSET)
        assertEquals(allocator, control.getAllocator(PlayerId.UNSET))
        control.getBackBufferDurationUs(PlayerId.UNSET)
        control.retainBackBufferFromKeyframe(PlayerId.UNSET)
        control.shouldContinuePreloading(PlayerId.UNSET, Timeline.EMPTY, MediaPeriodId(Any()), 0)
        control.shouldStartPlayback(parameters)
        assertEquals(
            listOf(
                "onPrepared",
                "onTracksSelected",
                "onStopped",
                "onReleased",
                "getAllocator",
                "getBackBufferDurationUs",
                "retainBackBufferFromKeyframe",
                "shouldContinuePreloading",
                "shouldStartPlayback",
            ),
            calls,
        )
    }

    // Kotlin's delegation would not forward a Java default method: a method that a later Media3
    // adds must be forwarded too.
    @Test
    fun theControlHasEachMethodOfLoadControl() {
        val missing =
            LoadControl::class.java.methods.filter { method ->
                !method.isAnnotationPresent(java.lang.Deprecated::class.java) &&
                    !Modifier.isStatic(method.modifiers) &&
                    runCatching {
                        CoreLoadControl::class
                            .java
                            .getDeclaredMethod(
                                method.name,
                                *method.parameterTypes,
                            )
                    }
                        .isFailure
            }
        assertEquals(emptyList(), missing.map { it.name })
    }

    private companion object {
        const val ALLOCATION_BYTES = 65_536
        const val LONG_BUFFER = 100.0
    }
}
