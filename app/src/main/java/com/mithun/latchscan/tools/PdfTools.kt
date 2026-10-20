package com.mithun.latchscan.tools

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.mithun.latchscan.scanner.ImageProcessor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ToolException(message: String) : Exception(message)

enum class PageSize(val label: String) { AUTO("Fit to page"), A4("A4"), LETTER("Letter") }

enum class ExportQuality(val label: String, val maxDim: Int, val jpeg: Int) {
    HIGH("High", 3000, 90),
    BALANCED("Balanced", 2200, 78),
    SMALL("Small", 1500, 60),
}

object PdfTools {

    private fun newDocument() = PDDocument(MemoryUsageSetting.setupMixed(24L * 1024 * 1024))

    /** Builds a PDF with one image per page. */
    fun imagesToPdf(
        images: List<File>,
        out: File,
        pageSize: PageSize = PageSize.AUTO,
        quality: ExportQuality = ExportQuality.HIGH,
        onProgress: (Float) -> Unit = {},
    ) {
        if (images.isEmpty()) throw ToolException("There are no pages to export")
        out.parentFile?.mkdirs()
        newDocument().use { doc ->
            images.forEachIndexed { i, file ->
                val bmp = ImageProcessor.decodeSampled(file, quality.maxDim) ?: throw ToolException("Page ${i + 1} couldn't be read")
                addImagePage(doc, bmp, pageSize, quality.jpeg)
                bmp.recycle()
                onProgress((i + 1f) / images.size)
            }
            doc.save(out)
        }
    }

    private fun addImagePage(doc: PDDocument, bmp: Bitmap, pageSize: PageSize, jpegQuality: Int) {
        val img = JPEGFactory.createFromImage(doc, bmp, jpegQuality / 100f)
        val landscape = bmp.width > bmp.height
        val rect = when (pageSize) {
            PageSize.AUTO -> {
                val w = if (landscape) PDRectangle.A4.height else PDRectangle.A4.width
                PDRectangle(w, w * bmp.height / bmp.width)
            }
            PageSize.A4 -> if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
            PageSize.LETTER -> if (landscape) PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width) else PDRectangle.LETTER
        }
        val page = PDPage(rect)
        doc.addPage(page)
        val margin = if (pageSize == PageSize.AUTO) 0f else 18f
        val availW = rect.width - 2 * margin
        val availH = rect.height - 2 * margin
        val s = min(availW / bmp.width, availH / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(img, (rect.width - w) / 2f, (rect.height - h) / 2f, w, h)
        }
    }

    /** Joins PDFs in the given order without re-encoding (text stays selectable). */
    fun merge(inputs: List<Pair<File, String>>, out: File) {
        if (inputs.isEmpty()) throw ToolException("Pick at least one PDF")
        // Check each file first so the error names the problem file.
        inputs.forEach { (f, name) -> checkReadable(f, name) }
        out.parentFile?.mkdirs()
        val merger = PDFMergerUtility()
        inputs.forEach { merger.addSource(it.first) }
        merger.destinationFileName = out.absolutePath
        try {
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw ToolException("One of the PDFs is password-protected")
        } catch (e: OutOfMemoryError) {
            throw ToolException("These PDFs are too large to merge on this phone")
        }
    }

    fun checkReadable(f: File, name: String): Int {
        try {
            PDDocument.load(f, MemoryUsageSetting.setupTempFileOnly()).use { d ->
                if (d.isEncrypted) throw ToolException("\"$name\" is password-protected")
                return d.numberOfPages
            }
        } catch (e: InvalidPasswordException) {
            throw ToolException("\"$name\" is password-protected")
        } catch (e: ToolException) {
            throw e
        } catch (e: Exception) {
            throw ToolException("\"$name\" isn't a readable PDF")
        }
    }

    fun pageCount(f: File): Int = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
    }.getOrDefault(0)

    /** Renders each page of a PDF to a bitmap (white background) at the given DPI. */
    fun renderPages(f: File, dpi: Int, maxDim: Int = 2500, block: (index: Int, count: Int, bmp: Bitmap, widthPt: Int, heightPt: Int) -> Unit) {
        val pfd = try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw ToolException("Couldn't open the PDF")
        }
        pfd.use {
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                throw ToolException("This PDF is password-protected")
            } catch (e: Exception) {
                throw ToolException("This file isn't a readable PDF")
            }
            renderer.use { r ->
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { page ->
                        val scale = dpi / 72f
                        var w = (page.width * scale).roundToInt().coerceAtLeast(1)
                        var h = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val longest = max(w, h)
                        if (longest > maxDim) {
                            val k = maxDim.toFloat() / longest
                            w = (w * k).roundToInt().coerceAtLeast(1)
                            h = (h * k).roundToInt().coerceAtLeast(1)
                        }
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        try {
                            block(i, r.pageCount, bmp, page.width, page.height)
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        }
    }

    data class CompressResult(val file: File, val size: Long, val reachedTarget: Boolean, val alreadySmall: Boolean)

    private fun jpegSize(bmp: Bitmap, q: Int): Int {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
        return bos.size()
    }

    private val DPIS = intArrayOf(150, 120, 100, 85, 72, 60, 50, 40)
    private val QUALITIES = intArrayOf(85, 75, 65, 55, 45, 35, 25, 18)

    /**
     * Shrinks a PDF to at most [targetBytes] by re-rendering every page as a JPEG,
     * choosing the sharpest resolution/quality pair that fits.
     */
    fun compressToSize(input: File, targetBytes: Long, out: File, onProgress: (Float, String) -> Unit): CompressResult {
        val original = input.length()
        if (original <= targetBytes) {
            input.copyTo(out, overwrite = true)
            return CompressResult(out, original, reachedTarget = true, alreadySmall = true)
        }
        val n = pageCount(input).takeIf { it > 0 } ?: run {
            renderPages(input, 10) { _, _, _, _, _ -> } // surfaces password / format errors
            throw ToolException("This PDF has no pages")
        }
        val sampleIdx = if (n <= 6) (0 until n).toSet() else (0 until 6).map { (it * (n - 1)) / 5 }.toSet()
        val overhead = 2_000L + n * 700L

        var chosen: Pair<Int, Int>? = null // dpi index, quality index
        loop@ for ((di, dpi) in DPIS.withIndex()) {
            onProgress(di.toFloat() / DPIS.size * 0.5f, "Testing ${dpi} dpi…")
            val sums = LongArray(QUALITIES.size)
            renderPages(input, dpi) { i, _, bmp, _, _ ->
                if (i in sampleIdx) QUALITIES.forEachIndexed { qi, q -> sums[qi] += jpegSize(bmp, q).toLong() }
            }
            for (qi in QUALITIES.indices) {
                val est = sums[qi] * n / sampleIdx.size + overhead
                if (est * 1.03 <= targetBytes) {
                    chosen = di to qi
                    break@loop
                }
            }
        }

        var (di, qi) = chosen ?: (DPIS.lastIndex to QUALITIES.lastIndex)
        var attempt = 0
        while (true) {
            onProgress(0.5f + 0.5f * attempt / 4f, "Building PDF…")
            buildRasterPdf(input, DPIS[di], QUALITIES[qi], out) { p -> onProgress(0.5f + 0.5f * p, "Building PDF…") }
            val size = out.length()
            if (size <= targetBytes) return CompressResult(out, size, reachedTarget = true, alreadySmall = false)
            attempt++
            // Step down: lower quality first, then resolution.
            when {
                qi < QUALITIES.lastIndex -> qi++
                di < DPIS.lastIndex -> { di++; qi = 2 }
                else -> return CompressResult(out, size, reachedTarget = false, alreadySmall = false)
            }
            if (attempt > 6) return CompressResult(out, size, reachedTarget = false, alreadySmall = false)
        }
    }

    private fun buildRasterPdf(input: File, dpi: Int, quality: Int, out: File, onProgress: (Float) -> Unit) {
        newDocument().use { doc ->
            renderPages(input, dpi) { i, count, bmp, wPt, hPt ->
                val img = JPEGFactory.createFromImage(doc, bmp, quality / 100f)
                val page = PDPage(PDRectangle(wPt.toFloat(), hPt.toFloat()))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, wPt.toFloat(), hPt.toFloat()) }
                onProgress((i + 1f) / count)
            }
            doc.save(out)
        }
    }
}
