package com.mithun.latchscan.scanner

import android.graphics.Bitmap
import com.mithun.latchscan.data.Pt
import com.mithun.latchscan.data.Quad
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds the outline of a sheet of paper in a greyscale frame.
 *
 * Pipeline (tuned on paper-on-table scenes: dark and light tables, wood grain, shadows,
 * a pen lying across the page):
 *  1. shrink to ~480px, median + Gaussian blur (kills text and table texture)
 *  2. build edge maps with three Canny sensitivities plus an Otsu threshold mask
 *  3. every large contour gives a candidate: its convex hull simplified to 4 corners,
 *     convex, at least 12% of the frame, not hugging the frame border
 *  4. each candidate's sides must actually run along edges in the picture ("edge support");
 *     the winner is the largest well-supported candidate.
 */
object EdgeDetector {

    private const val WORK_SIZE = 480.0
    private const val MIN_AREA_FRACTION = 0.12
    private const val MAX_AREA_FRACTION = 0.97
    private const val MIN_SUPPORT = 0.75

    private class Candidate(val pts: Array<Point>, val area: Double, val support: Double)

    /** [gray] is a single-channel 8-bit Mat. Returns normalised corners, or null. */
    fun detect(gray: Mat): Quad? {
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
            val best = candidates
                .filter { it.support >= MIN_SUPPORT }
                .maxByOrNull { it.area * it.support }
                ?: return null
            return Quad.ordered(best.pts.map { Pt((it.x / w).toFloat(), (it.y / h).toFloat()) })
        } finally {
            small.release(); blurred.release(); kernel3.release(); kernel9.release(); supportMat.release()
            maps.forEach { it.release() }
        }
    }

    fun detect(bitmap: Bitmap): Quad? {
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            detect(gray)
        } catch (t: Throwable) {
            null
        } finally {
            rgba.release(); gray.release()
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
                    val q = quadFrom(c, minArea, frameArea, w, h) ?: return@forEach
                    out.add(Candidate(q.first, q.second, sideSupport(q.first, support, w, h)))
                }
        } finally {
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

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
            worst = minOf(worst, if (total == 0) 0.0 else hit.toDouble() / total)
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
}
