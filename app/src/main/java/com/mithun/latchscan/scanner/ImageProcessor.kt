package com.mithun.latchscan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.mithun.latchscan.data.Filter
import com.mithun.latchscan.data.Page
import com.mithun.latchscan.data.Quad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageProcessor {

    /** Longest side kept for originals and processed pages (~300 dpi on A4). */
    const val MAX_DIM = 3000

    fun decodeSampled(file: File, maxDim: Int = MAX_DIM): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return scaleDown(bmp, maxDim)
    }

    /** Decodes an image picked from the gallery / files, applying its EXIF rotation. */
    fun decodeUri(context: Context, uri: Uri, maxDim: Int = MAX_DIM): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val upright = if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it != bmp) bmp.recycle() }
        return scaleDown(upright, maxDim)
    }

    fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxDim) s *= 2
        return s
    }

    fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val f = maxDim.toFloat() / longest
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * f).roundToInt().coerceAtLeast(1), (bmp.height * f).roundToInt().coerceAtLeast(1), true)
        if (out != bmp) bmp.recycle()
        return out
    }

    fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bmp
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out != bmp) bmp.recycle()
        return out
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 90) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** Perspective-corrects [src] to the rectangle described by [quad] (normalised corners). */
    fun warp(src: Bitmap, quad: Quad): Bitmap {
        if (quad == Quad.FULL || !quad.isValid()) return src.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val p = quad.points.map { Point(it.x * w, it.y * h) }
        val top = hypot(p[1].x - p[0].x, p[1].y - p[0].y)
        val bottom = hypot(p[2].x - p[3].x, p[2].y - p[3].y)
        val left = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val right = hypot(p[2].x - p[1].x, p[2].y - p[1].y)
        val outW = max(top, bottom).roundToInt().coerceIn(16, MAX_DIM)
        val outH = max(left, right).roundToInt().coerceIn(16, MAX_DIM)

        val srcMat = Mat()
        val dstMat = Mat()
        val srcPts = MatOfPoint2f(*p.toTypedArray())
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0), Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0),
        )
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        try {
            Utils.bitmapToMat(src, srcMat)
            Imgproc.warpPerspective(srcMat, dstMat, transform, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_LINEAR)
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, out)
            return out
        } finally {
            srcMat.release(); dstMat.release(); srcPts.release(); dstPts.release(); transform.release()
        }
    }

    /** Applies a scan filter. Returns a new bitmap; [src] is left untouched. */
    fun applyFilter(src: Bitmap, filter: Filter): Bitmap {
        if (filter == Filter.ORIGINAL) return src.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        val out = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            when (filter) {
                Filter.ENHANCED -> enhanceColor(rgba, out)
                Filter.GRAY -> {
                    val g = Mat()
                    Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
                    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                    clahe.apply(g, g)
                    Imgproc.cvtColor(g, out, Imgproc.COLOR_GRAY2RGBA)
                    g.release()
                }
                Filter.BW -> {
                    val g = Mat()
                    Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
                    val flat = Mat()
                    flattenLighting(g, flat)
                    val bw = Mat()
                    val block = (max(g.cols(), g.rows()) / 60).let { if (it % 2 == 0) it + 1 else it }.coerceIn(15, 61)
                    Imgproc.adaptiveThreshold(flat, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
                    Imgproc.cvtColor(bw, out, Imgproc.COLOR_GRAY2RGBA)
                    g.release(); flat.release(); bw.release()
                }
                Filter.ORIGINAL -> rgba.copyTo(out)
            }
            val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bmp)
            return bmp
        } finally {
            rgba.release(); out.release()
        }
    }

    /** Divides out uneven lighting (shadows, vignetting) so paper becomes white. */
    private fun flattenLighting(gray: Mat, dst: Mat) {
        val bg = Mat()
        val k = max(7, min(gray.cols(), gray.rows()) / 120).let { if (it % 2 == 0) it + 1 else it }
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
        Imgproc.dilate(gray, bg, kernel)
        val small = Mat()
        val factor = 0.25
        Imgproc.resize(bg, small, Size(), factor, factor, Imgproc.INTER_AREA)
        Imgproc.medianBlur(small, small, 21)
        Imgproc.resize(small, bg, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.divide(gray, bg, dst, 255.0)
        bg.release(); kernel.release(); small.release()
    }

    private fun enhanceColor(rgba: Mat, out: Mat) {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val channels = ArrayList<Mat>()
        Core.split(rgb, channels)
        val flatChannels = channels.map { ch -> Mat().also { flattenLighting(ch, it) } }
        val merged = Mat()
        Core.merge(flatChannels, merged)
        // Gentle contrast/saturation lift so ink stays crisp.
        merged.convertTo(merged, CvType.CV_8UC3, 1.18, -24.0)
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_RGB2RGBA)
        rgb.release(); merged.release()
        channels.forEach { it.release() }
        flatChannels.forEach { it.release() }
    }

    /** Full pipeline for one page: original -> crop/warp -> filter -> rotate -> JPEG. */
    fun render(original: File, page: Page, out: File, quality: Int = 90): Boolean = try {
        renderAt(original, page, out, quality, MAX_DIM)
    } catch (e: OutOfMemoryError) {
        // Low-memory phones: retry at a smaller (still ~200 dpi) size.
        System.gc()
        renderAt(original, page, out, quality, 1800)
    }

    private fun renderAt(original: File, page: Page, out: File, quality: Int, maxDim: Int): Boolean {
        val src = decodeSampled(original, maxDim) ?: return false
        val warped = warp(src, page.quad)
        src.recycle()
        val filtered = applyFilter(warped, page.filter)
        warped.recycle()
        val rotated = rotate(filtered, page.rotation)
        saveJpeg(rotated, out, quality)
        rotated.recycle()
        return true
    }

    /**
     * Places a front and back scan on one A4 sheet (ID-card / two-sided style),
     * front on top, back below.
     */
    fun combineFrontBack(front: File, back: File, out: File): Boolean {
        val a = decodeSampled(front, 2000) ?: return false
        val b = decodeSampled(back, 2000) ?: return false.also { a.recycle() }
        val pageW = 2480
        val pageH = 3508
        val canvasBmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvasBmp)
        c.drawColor(Color.WHITE)
        val margin = 140f
        val gap = 120f
        val slotH = (pageH - 2 * margin - gap) / 2f
        val slotW = pageW - 2 * margin
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        listOf(a to margin, b to margin + slotH + gap).forEach { (bmp, top) ->
            val s = min(slotW / bmp.width, slotH / bmp.height)
            val dw = bmp.width * s
            val dh = bmp.height * s
            val left = (pageW - dw) / 2f
            val t = top + (slotH - dh) / 2f
            c.drawBitmap(bmp, null, RectF(left, t, left + dw, t + dh), paint)
        }
        a.recycle(); b.recycle()
        saveJpeg(canvasBmp, out, 90)
        canvasBmp.recycle()
        return true
    }
}
