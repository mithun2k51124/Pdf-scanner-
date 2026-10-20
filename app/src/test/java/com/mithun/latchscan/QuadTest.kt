package com.mithun.latchscan

import com.mithun.latchscan.data.Pt
import com.mithun.latchscan.data.Quad
import com.mithun.latchscan.scanner.FrameAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadTest {

    @Test
    fun ordersCornersClockwiseFromTopLeft() {
        val q = Quad.ordered(listOf(Pt(0.9f, 0.85f), Pt(0.1f, 0.1f), Pt(0.12f, 0.9f), Pt(0.88f, 0.12f)))
        assertEquals(Pt(0.1f, 0.1f), q.tl)
        assertEquals(Pt(0.88f, 0.12f), q.tr)
        assertEquals(Pt(0.9f, 0.85f), q.br)
        assertEquals(Pt(0.12f, 0.9f), q.bl)
    }

    @Test
    fun validityRejectsCrossedCorners() {
        assertTrue(Quad.FULL.isValid())
        val crossed = Quad(Pt(0f, 0f), Pt(1f, 1f), Pt(1f, 0f), Pt(0f, 1f))
        assertFalse(crossed.isValid())
        val tiny = Quad(Pt(0.5f, 0.5f), Pt(0.51f, 0.5f), Pt(0.51f, 0.51f), Pt(0.5f, 0.51f))
        assertFalse(tiny.isValid())
    }

    @Test
    fun rotatesSensorCoordinatesToUpright() {
        val q = Quad.ordered(listOf(Pt(0.1f, 0.2f), Pt(0.6f, 0.2f), Pt(0.6f, 0.9f), Pt(0.1f, 0.9f)))
        val r = FrameAnalyzer.rotateQuad(q, 90)
        // 90° clockwise: (x, y) -> (1 - y, x)
        assertEquals(0.1f, r.tl.x, 1e-5f)
        assertEquals(0.1f, r.tl.y, 1e-5f)
        assertEquals(0.8f, r.tr.x, 1e-5f)
        assertEquals(0.6f, r.br.y, 1e-5f)
        assertTrue(r.isValid())
        assertEquals(q, FrameAnalyzer.rotateQuad(q, 0))
    }

    @Test
    fun maxShiftMeasuresLargestCornerMove() {
        val a = Quad.inset(0.1f)
        val b = a.with(2, Pt(0.9f, 0.95f))
        assertEquals(0.05f, a.maxShift(b), 1e-5f)
    }
}
