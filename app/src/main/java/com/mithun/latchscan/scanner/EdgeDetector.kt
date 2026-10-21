package com.mithun.latchscan.scanner

import android.graphics.Bitmap
import android.util.Log
import com.mithun.latchscan.data.Pt
import com.mithun.latchscan.data.Quad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the four corners of a document (paper, book page, receipt, card) in a photo or
 * camera frame. Three detectors work together:
 *
 *  1. **Corner AI** ([CornerModel], DocAligner heatmap network): robust to low contrast
 *     (white paper on a white table), clutter and soft shadows.
 *  2. **Contour detector**: precise edges; also handles pages that nearly fill the frame,
 *     where the AI is unsure.
 *  3. **Line detector**: when the AI sees a document but not all four corners (an open
 *     book whose other page and spine hide a corner), builds the page from straight edges.
 *
 * When the AI and the contours agree, the contour corners win because they sit exactly
 * on the paper edge.
 */
object EdgeDetector {

    private const val TAG = "EdgeDetector"
    private const val WORK_SIZE = 480.0
    private const val MIN_AREA_FRACTION = 0.12
    private const val MAX_AREA_FRACTION = 0.97
    private const val MIN_SUPPORT = 0.75

    /** A candidate outline in normalised coordinates. */
    class Candidate(val quad: Quad, val area: Double, val support: Double)

    /** Everything the contour stage learned about one frame. */
    private class Analysis(val candidates: List<Candidate>, val support: ByteArray, val w: Int, val h: Int, val blurred: Mat)

    /** Detects a document in an 8-bit BGR (3-channel) or RGBA image. Returns normalised corners or null. */
    fun detectColor(color: Mat): Quad? {
        if (color.empty()) return null
        val gray = Mat()
        val bgr = Mat()
        try {
            if (color.channels() == 4) {
                Imgproc.cvtColor(color, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.cvtColor(color, bgr, Imgproc.COLOR_RGBA2BGR)
            } else {
                Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
                color.copyTo(bgr)
            }
            val ai = runCatching { CornerModel.get()?.predict(bgr) }
                .onFailure { Log.w(TAG, "Corner model failed", it) }
                .getOrNull()
            return combine(gray, ai)
        } finally {
            gray.release(); bgr.release()
        }
    }

    /** Contour-only detection on a greyscale image (used when no colour is available). */
    fun detect(gray: Mat): Quad? = combine(gray, null)

    fun detect(bitmap: Bitmap): Quad? {
        val rgba = Mat()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            detectColor(rgba)
        } catch (t: Throwable) {
            Log.w(TAG, "detect failed", t)
            null
        } finally {
            rgba.release()
        }
    }

    private fun combine(gray: Mat, ai: CornerModel.Result?): Quad? {
        val a = analyze(gray) ?: return null
        try {
            val classic = a.candidates.filter { it.support >= MIN_SUPPORT }.maxByOrNull { it.area * it.support }

            var model: Candidate? = null
            var modelConf = 0f
            if (ai != null && ai.corners.size == 4 && ai.minConfidence >= 0.5f) {
                val q = Quad.ordered(ai.corners)
                val area = abs(q.area()).toDouble()
                if (q.isValid() && area >= 0.06) {
                    modelConf = ai.minConfidence
                    // Snap to a contour that matches the AI's corners: same page, sharper edges.
                    val near = a.candidates.filter { it.support >= 0.5 && it.quad.maxShift(q) < 0.045f }.maxByOrNull { it.support }
                    model = near ?: Candidate(q, area, support(q, a))
                }
            }

            val chosen = when {
                classic != null && model != null -> when {
                    classic.quad.maxShift(model.quad) < 0.04f -> classic
                    modelConf >= 0.75f && model.support >= classic.support - 0.15 -> model
                    else -> classic
                }
                model != null -> model
                classic != null -> classic
                // The AI sees part of a document (e.g. a book page with a hidden corner): try straight lines.
                ai != null && ai.corners.size >= 2 -> lineCandidate(a)
                else -> null
            } ?: return null
            return shrink(chosen.quad, 0.992f)
        } finally {
            a.blurred.release()
        }
    }

    /** Pulls corners in very slightly so no sliver of table shows along the page edge. */
    private fun shrink(q: Quad, k: Float): Quad {
        val cx = q.points.sumOf { it.x.toDouble() }.toFloat() / 4
        val cy = q.points.sumOf { it.y.toDouble() }.toFloat() / 4
        return Quad.ordered(q.points.map { Pt(cx + (it.x - cx) * k, cy + (it.y - cy) * k) })
    }

    private fun analyze(gray: Mat): Analysis? {
        if (gray.empty()) return null
        val scale = WORK_SIZE / max(gray.cols(), gray.rows())
        val small = Mat()
        val blurred = Mat()
        val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val kernel9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val maps = ArrayList<Mat>(4)
        val supportMat = Mat()
        try {
            if (scale < 1.0) {
                Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                gray.copyTo(small)
            }
            val w = small.cols()
            val h = small.rows()
            Imgproc.medianBlur(small, blurred, 7)
            Imgproc.GaussianBlur(blurred, blurred, Size(5.0, 5.0), 0.0)

            for ((lo, hi) in listOf(20.0 to 60.0, 40.0 to 120.0, 70.0 to 180.0)) {
                val e = Mat()
                Imgproc.Canny(blurred, e, lo, hi)
                Imgproc.dilate(e, e, kernel3)
                maps.add(e)
            }
            val t = Mat()
            Imgproc.threshold(blurred, t, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(t, t, Imgproc.MORPH_CLOSE, kernel9)
            maps.add(t)

            Imgproc.Canny(blurred, supportMat, 30.0, 90.0)
            Imgproc.dilate(supportMat, supportMat, kernel9)
            val support = ByteArray(w * h)
            supportMat.get(0, 0, support)

            val frameArea = (w * h).toDouble()
            val candidates = ArrayList<Candidate>()
            for (m in maps) collectCandidates(m, frameArea, w, h, support, candidates)
            return Analysis(candidates, support, w, h, blurred.clone())
        } finally {
            small.release(); blurred.release(); kernel3.release(); kernel9.release(); supportMat.release()
            maps.forEach { it.release() }
        }
    }

    private fun collectCandidates(binary: Mat, frameArea: Double, w: Int, h: Int, support: ByteArray, out: MutableList<Candidate>) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val minArea = frameArea * MIN_AREA_FRACTION
            contours
                .map { it to Imgproc.contourArea(it) }
                .filter { it.second >= minArea }
                .sortedByDescending { it.second }
                .take(10)
                .forEach { (c, _) ->
                    val pts = quadFrom(c, minArea, frameArea, w, h) ?: return@forEach
                    val q = Quad.ordered(pts.first.map { Pt((it.x / w).toFloat(), (it.y / h).toFloat()) })
                    out.add(Candidate(q, pts.second / frameArea, sideSupport(pts.first, support, w, h)))
                }
        } finally {
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun support(q: Quad, a: Analysis): Double =
        sideSupport(q.points.map { Point(it.x.toDouble() * a.w, it.y.toDouble() * a.h) }.toTypedArray(), a.support, a.w, a.h)

    /** Smallest fraction (over the four sides) of sample points that land on an edge. */
    private fun sideSupport(pts: Array<Point>, support: ByteArray, w: Int, h: Int): Double {
        var worst = 1.0
        val n = 40
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            var hit = 0
            var total = 0
            for (k in 0 until n) {
                val t = 0.08 + 0.84 * k / (n - 1)
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                if (x in 0 until w && y in 0 until h) {
                    total++
                    if (support[y * w + x].toInt() != 0) hit++
                }
            }
            worst = minOf(worst, if (total < n * 0.6) 0.0 else hit.toDouble() / total)
        }
        return worst
    }

    private fun quadFrom(contour: MatOfPoint, minArea: Double, frameArea: Double, w: Int, h: Int): Pair<Array<Point>, Double>? {
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val all = contour.toArray()
        val hullPts = hullIdx.toArray().map { all[it] }.toTypedArray()
        hullIdx.release()
        if (hullPts.size < 4) return null
        val hull2f = MatOfPoint2f(*hullPts)
        try {
            val peri = Imgproc.arcLength(hull2f, true)
            for (eps in doubleArrayOf(0.02, 0.03, 0.045, 0.06, 0.08)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
                val pts = approx.toArray()
                approx.release()
                if (pts.size < 4) return null
                if (pts.size == 4) {
                    val asInt = MatOfPoint(*pts.map { Point(it.x.roundToInt().toDouble(), it.y.roundToInt().toDouble()) }.toTypedArray())
                    val area = Imgproc.contourArea(asInt)
                    val convex = Imgproc.isContourConvex(asInt)
                    asInt.release()
                    val ok = area > minArea && area < MAX_AREA_FRACTION * frameArea && convex && borderHits(pts, w, h) < 2
                    return if (ok) pts to area else null
                }
            }
            return null
        } finally {
            hull2f.release()
        }
    }

    private fun borderHits(pts: Array<Point>, w: Int, h: Int): Int {
        val m = 4.0
        return pts.count { it.x < m || it.y < m || it.x > w - 1 - m || it.y > h - 1 - m }
    }

    // ---------------------------------------------------------------------------------
    // Line detector (fallback for book pages): two roughly horizontal + two roughly
    // vertical straight edges whose quad is brighter inside than around it.
    // ---------------------------------------------------------------------------------

    private class Seg(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
        val angle: Double get() = (Math.toDegrees(atan2(y2 - y1, x2 - x1)) + 180.0) % 180.0
        val length: Double get() = hypot(x2 - x1, y2 - y1)
    }

    private fun intersect(a: Seg, b: Seg): Point? {
        val d = (a.x1 - a.x2) * (b.y1 - b.y2) - (a.y1 - a.y2) * (b.x1 - b.x2)
        if (abs(d) < 1e-6) return null
        val c1 = a.x1 * a.y2 - a.y1 * a.x2
        val c2 = b.x1 * b.y2 - b.y1 * b.x2
        return Point((c1 * (b.x1 - b.x2) - (a.x1 - a.x2) * c2) / d, (c1 * (b.y1 - b.y2) - (a.y1 - a.y2) * c2) / d)
    }

    private fun dedupe(lines: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        for (l in lines.sortedByDescending { it.length }) {
            val mx = (l.x1 + l.x2) / 2
            val my = (l.y1 + l.y2) / 2
            val dup = out.any { o ->
                val da = abs(l.angle - o.angle).let { min(it, 180 - it) }
                val dist = abs((o.x2 - o.x1) * (o.y1 - my) - (o.x1 - mx) * (o.y2 - o.y1)) / (o.length + 1e-6)
                da < 6 && dist < 10
            }
            if (!dup) out.add(l)
            if (out.size >= 10) break
        }
        return out
    }

    private fun lineCandidate(a: Analysis): Candidate? {
        val w = a.w
        val h = a.h
        val edges = Mat()
        val blackhat = Mat()
        val bhEdges = Mat()
        val lineMap = Mat()
        val lines = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
        val dil5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try {
            Imgproc.Canny(a.blurred, edges, 30.0, 90.0)
            // Dark creases (a book's gutter) show up in the black-hat image.
            Imgproc.morphologyEx(a.blurred, blackhat, Imgproc.MORPH_BLACKHAT, kernel)
            Imgproc.GaussianBlur(blackhat, blackhat, Size(9.0, 9.0), 0.0)
            Imgproc.Canny(blackhat, bhEdges, 10.0, 30.0)
            Core.bitwise_or(edges, bhEdges, lineMap)
            val minLen = 0.25 * min(w, h)
            Imgproc.HoughLinesP(lineMap, lines, 1.0, Math.PI / 180, 60, minLen, 12.0)
            if (lines.empty()) return null
            val segs = ArrayList<Seg>()
            val buf = IntArray(4)
            for (i in 0 until lines.rows()) {
                lines.get(i, 0, buf)
                segs.add(Seg(buf[0].toDouble(), buf[1].toDouble(), buf[2].toDouble(), buf[3].toDouble()))
            }
            val horiz = dedupe(segs.filter { min(it.angle, 180 - it.angle) < 35 })
            val vert = dedupe(segs.filter { abs(it.angle - 90) < 35 })
            if (horiz.size < 2 || vert.size < 2) return null

            Imgproc.dilate(lineMap, lineMap, dil5)
            val sup = ByteArray(w * h)
            lineMap.get(0, 0, sup)
            val gray = ByteArray(w * h)
            a.blurred.get(0, 0, gray)

            var best: Candidate? = null
            var bestScore = 0.0
            for (i in horiz.indices) for (j in i + 1 until horiz.size) for (k in vert.indices) for (l in k + 1 until vert.size) {
                val p = listOf(
                    intersect(horiz[i], vert[k]), intersect(horiz[i], vert[l]),
                    intersect(horiz[j], vert[l]), intersect(horiz[j], vert[k]),
                )
                if (p.any { it == null }) continue
                val pts = p.filterNotNull()
                if (pts.any { it.x < -0.02 * w || it.x > 1.02 * w || it.y < -0.02 * h || it.y > 1.02 * h }) continue
                val q = Quad.ordered(pts.map { Pt((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) })
                val area = abs(q.area()).toDouble()
                if (area < 0.15 || area > 0.97 || !q.isValid()) continue
                val px = q.points.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }.toTypedArray()
                val sides = (0 until 4).map { singleSide(px[it], px[(it + 1) % 4], sup, w, h) }
                val minSide = sides.min()
                if (minSide < 0.6) continue
                if (!brighterInside(px, gray, w, h)) continue
                val score = area * sides.average() * minSide
                if (score > bestScore) {
                    bestScore = score
                    best = Candidate(q, area, minSide)
                }
            }
            return best
        } finally {
            edges.release(); blackhat.release(); bhEdges.release(); lineMap.release(); lines.release(); kernel.release(); dil5.release()
        }
    }

    private fun singleSide(a: Point, b: Point, sup: ByteArray, w: Int, h: Int): Double {
        val n = 30
        var hit = 0
        var total = 0
        for (k in 0 until n) {
            val t = 0.06 + 0.88 * k / (n - 1)
            val x = (a.x + (b.x - a.x) * t).roundToInt()
            val y = (a.y + (b.y - a.y) * t).roundToInt()
            if (x in 0 until w && y in 0 until h) {
                total++
                if (sup[y * w + x].toInt() != 0) hit++
            }
        }
        return if (total < n * 0.6) 0.0 else hit.toDouble() / total
    }

    /** Paper is brighter than what surrounds it: compare inside the quad with a band just outside. */
    private fun brighterInside(px: Array<Point>, gray: ByteArray, w: Int, h: Int): Boolean {
        val mask = Mat.zeros(h, w, org.opencv.core.CvType.CV_8UC1)
        val ring = Mat()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
        val img = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
        try {
            img.put(0, 0, gray)
            val poly = MatOfPoint(*px.map { Point(it.x, it.y) }.toTypedArray())
            Imgproc.fillConvexPoly(mask, poly, Scalar(255.0))
            poly.release()
            Imgproc.dilate(mask, ring, k)
            Core.subtract(ring, mask, ring)
            val inside = Core.mean(img, mask).`val`[0]
            val outside = Core.mean(img, ring).`val`[0]
            return inside >= outside + 8
        } finally {
            mask.release(); ring.release(); k.release(); img.release()
        }
    }
}
