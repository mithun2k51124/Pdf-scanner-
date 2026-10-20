package com.mithun.latchscan.scanner

import com.mithun.latchscan.data.Quad

/**
 * Decides when auto mode should take the photo.
 *
 * The page outline must stay within [tolerance] (fraction of the frame) for [holdMs].
 * After a capture the tracker "disarms" until the page is removed (outline lost for a few
 * frames) or moved a lot, so the same page is never captured twice.
 */
class LatchTracker(
    private val holdMs: Long = 1100L,
    private val tolerance: Float = 0.022f,
    private val rearmShift: Float = 0.12f,
    private val lostFramesToRearm: Int = 5,
) {
    data class State(val progress: Float, val capture: Boolean)

    private var reference: Quad? = null
    private var stableSince: Long? = null
    private var lostFrames = 0
    private var armed = true
    private var lastCaptured: Quad? = null

    /** Feed one detection. Returns the hold progress (0..1) and whether to shoot now. */
    fun update(quad: Quad?, timeMs: Long, autoEnabled: Boolean): State {
        if (quad == null) {
            lostFrames++
            if (lostFrames > lostFramesToRearm) {
                reference = null
                stableSince = null
                armed = true // page removed: ready for the next one
            }
            return State(0f, false)
        }
        lostFrames = 0
        if (!armed) {
            val last = lastCaptured
            if (last == null || quad.maxShift(last) > rearmShift) armed = true else return State(0f, false)
        }
        val ref = reference
        if (ref == null || quad.maxShift(ref) > tolerance) {
            reference = quad
            stableSince = timeMs
            return State(0f, false)
        }
        val since = stableSince ?: timeMs.also { stableSince = it }
        val progress = ((timeMs - since).toFloat() / holdMs).coerceIn(0f, 1f)
        return State(progress, autoEnabled && progress >= 1f)
    }

    /** Call after a photo was taken (by auto mode or the shutter button). */
    fun onCaptured(quad: Quad?) {
        lastCaptured = quad ?: reference
        armed = false
        stableSince = null
        reference = null
    }

    /** Call when a capture failed, so the next steady frame can try again. */
    fun onCaptureFailed() {
        armed = true
        stableSince = null
    }

    fun resetHold() {
        stableSince = null
        reference = null
    }
}
