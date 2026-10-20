package com.mithun.latchscan.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import com.mithun.latchscan.scanner.ImageProcessor
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ImageFormat(val label: String, val ext: String) { JPEG("JPG", "jpg"), WEBP("WebP", "webp") }

object ImageTools {

    data class Result(val file: File, val size: Long, val width: Int, val height: Int, val quality: Int, val reachedTarget: Boolean)

    @Suppress("DEPRECATION")
    private fun encode(bmp: Bitmap, format: ImageFormat, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val f = when (format) {
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        }
        bmp.compress(f, quality, bos)
        return bos.toByteArray()
    }

    /** Highest quality in [lo, hi] whose output fits, found by binary search. */
    private fun bestQuality(bmp: Bitmap, format: ImageFormat, target: Long, lo: Int = 8, hi: Int = 95): Pair<Int, ByteArray>? {
        var l = lo
        var h = hi
        var best: Pair<Int, ByteArray>? = null
        while (l <= h) {
            val mid = (l + h) / 2
            val bytes = encode(bmp, format, mid)
            if (bytes.size <= target) {
                best = mid to bytes
                l = mid + 1
            } else {
                h = mid - 1
            }
        }
        return best
    }

    /** JPEG has no transparency — flatten onto white so transparent PNGs don't turn black. */
    private fun opaque(bmp: Bitmap): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bmp, 0f, 0f, null)
        }
        bmp.recycle()
        return out
    }

    /**
     * Compresses an image to at most [targetBytes]. Keeps full resolution when possible and
     * only shrinks the pixel size when even low quality would not fit.
     */
    fun compressToSize(ctx: Context, uri: Uri, targetBytes: Long, format: ImageFormat, maxDim: Int?, out: File): Result {
        var bmp = ImageProcessor.decodeUri(ctx, uri, maxDim ?: 4096) ?: throw ToolException("Couldn't open this image")
        if (format == ImageFormat.JPEG) bmp = opaque(bmp)
        try {
            var attempt = 0
            while (true) {
                val found = bestQuality(bmp, format, targetBytes, lo = if (attempt == 0) 30 else 12)
                if (found != null) {
                    out.parentFile?.mkdirs()
                    out.writeBytes(found.second)
                    return Result(out, found.second.size.toLong(), bmp.width, bmp.height, found.first, true)
                }
                attempt++
                val atMid = encode(bmp, format, 50).size.toLong()
                val factor = sqrt(targetBytes.toDouble() / atMid).coerceIn(0.3, 0.9)
                val nw = (bmp.width * factor).roundToInt()
                val nh = (bmp.height * factor).roundToInt()
                if (max(nw, nh) < 64 || attempt > 8) {
                    val bytes = encode(bmp, format, 10)
                    out.writeBytes(bytes)
                    return Result(out, bytes.size.toLong(), bmp.width, bmp.height, 10, bytes.size <= targetBytes)
                }
                val smaller = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                if (smaller != bmp) bmp.recycle()
                bmp = smaller
            }
        } finally {
            bmp.recycle()
        }
    }
}
