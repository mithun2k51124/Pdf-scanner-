package com.mithun.latchscan.tools

import android.content.Context
import android.graphics.Bitmap
import com.mithun.latchscan.data.DocumentRepository
import com.mithun.latchscan.data.ScanDocument
import com.mithun.latchscan.scanner.ImageProcessor
import com.mithun.latchscan.util.Files
import java.io.ByteArrayOutputStream
import java.io.File

/** Turns a saved scan into shareable files. */
class Exporter(private val ctx: Context, private val repo: DocumentRepository) {

    private fun pageFiles(doc: ScanDocument): List<File> {
        val files = doc.pages.map { repo.processedFile(doc, it) }
        val missing = files.indexOfFirst { !it.exists() }
        if (missing >= 0) throw ToolException("Page ${missing + 1} is missing — try re-cropping it")
        return files
    }

    fun pdf(doc: ScanDocument, size: PageSize, quality: ExportQuality, onProgress: (Float) -> Unit = {}): File {
        val out = Files.exportFile(ctx, doc.name, "pdf")
        PdfTools.imagesToPdf(pageFiles(doc), out, size, quality, onProgress)
        return out
    }

    fun docx(doc: ScanDocument, onProgress: (Float) -> Unit = {}): File {
        val files = pageFiles(doc)
        val builder = DocxBuilder()
        files.forEachIndexed { i, f ->
            val bmp = ImageProcessor.decodeSampled(f, 2200) ?: throw ToolException("Page ${i + 1} couldn't be read")
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            if (i > 0) builder.pageBreak()
            builder.image(bytes, bmp.width, bmp.height)
            bmp.recycle()
            onProgress((i + 1f) / files.size)
        }
        val out = Files.exportFile(ctx, doc.name, "docx")
        builder.write(out)
        return out
    }

    fun images(doc: ScanDocument): List<File> {
        val files = pageFiles(doc)
        val dir = Files.exportFile(ctx, "x", "tmp").parentFile!!
        return files.mapIndexed { i, f ->
            val name = Files.safeName(doc.name) + (if (files.size > 1) " - page ${i + 1}" else "") + ".jpg"
            f.copyTo(File(dir, name), overwrite = true)
        }
    }
}
