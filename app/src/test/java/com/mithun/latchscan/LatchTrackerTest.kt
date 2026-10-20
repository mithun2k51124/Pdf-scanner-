package com.mithun.latchscan

import com.mithun.latchscan.data.Pt
import com.mithun.latchscan.data.Quad
import com.mithun.latchscan.scanner.LatchTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatchTrackerTest {

    private val page = Quad.inset(0.15f)
    private fun jitter(q: Quad, d: Float) = Quad(
        Pt(q.tl.x + d, q.tl.y), Pt(q.tr.x, q.tr.y - d), Pt(q.br.x - d, q.br.y), Pt(q.bl.x, q.bl.y + d),
    )

    /** Feeds frames every 70 ms (the analyzer's rate) and returns the times at which it fired. */
    private fun run(t: LatchTracker, frames: List<Quad?>, auto: Boolean = true): List<Long> {
        val fired = ArrayList<Long>()
        frames.forEachIndexed { i, q ->
            val time = i * 70L
            if (t.update(q, time, auto).capture) {
                fired.add(time)
                t.onCaptured(q)
            }
        }
        return fired
    }

    @Test
    fun firesOnceAfterHoldingStill() {
        val fired = run(LatchTracker(), List(60) { page })
        assertEquals(1, fired.size)
        assertTrue("fired at ${fired[0]}", fired[0] in 1100L..1250L)
    }

    @Test
    fun smallHandShakeStillLatches() {
        val frames = List(40) { i -> jitter(page, if (i % 2 == 0) 0.008f else -0.008f) }
        assertEquals(1, run(LatchTracker(), frames).size)
    }

    @Test
    fun movingPageResetsTheHold() {
        // The page slides a little every 6 frames (~420 ms), never holding for 1.1 s.
        val frames = List(80) { i -> jitter(page, (i / 6) * 0.03f % 0.2f) }
        assertTrue(run(LatchTracker(), frames).isEmpty())
    }

    @Test
    fun nextPageNeedsTheOldOneRemoved() {
        val frames = List(40) { page } + List(8) { null } + List(40) { page }
        assertEquals(2, run(LatchTracker(), frames).size)
        // Without removing the page in between it captures only once.
        assertEquals(1, run(LatchTracker(), List(90) { page }).size)
    }

    @Test
    fun manualModeShowsProgressButNeverFires() {
        val t = LatchTracker()
        var last = LatchTracker.State(0f, false)
        repeat(40) { i -> last = t.update(page, i * 70L, autoEnabled = false) }
        assertEquals(1f, last.progress)
        assertFalse(last.capture)
    }
}
