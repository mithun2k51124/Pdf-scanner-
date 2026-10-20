package com.mithun.latchscan.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mithun.latchscan.scanner.ImageProcessor
import com.mithun.latchscan.util.Files
import com.mithun.latchscan.util.Mime
import com.mithun.latchscan.util.PickedFile
import java.io.ByteArrayOutputStream
import java.io.File

enum class OutputFormat(val label: String, val ext: String) { PDF("PDF", "pdf"), DOCX("Word (.docx)", "docx") }

enum class InputKind { PDF, IMAGE, DOCX, TEXT, UNSUPPORTED }

fun PickedFile.kind(): InputKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime == Mime.PDF || ext == "pdf" -> InputKind.PDF
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif") -> InputKind.IMAGE
        mime == Mime.DOCX || ext == "docx" -> InputKind.DOCX
        mime.startsWith("text/") || ext in setOf("txt", "md", "csv") -> InputKind.TEXT
        else -> InputKind.UNSUPPORTED
    }
}

/**
 * Merges any mix of PDFs, images, Word (.docx) and text files into one PDF or one .docx,
 * in the order given.
 */
class MergeEngine(private val ctx: Context) {

    fun merge(inputs: List<PickedFile>, format: OutputFormat, out: File, onProgress: (Float, String) -> Unit) {
        if (inputs.isEmpty()) throw ToolException("Add at least one file")
        inputs.firstOrNull { it.kind() == InputKind.UNSUPPORTED }?.let {
            val hint = if (it.name.lowercase().endsWith(".doc")) " Old .doc files aren't supported — save it as .docx first." else ""
            throw ToolException("\"${it.name}\" can't be merged.$hint")
        }
        val work = Files.workDir(ctx)
        try {
            when (format) {
                OutputFormat.PDF -> toPdf(inputs, work, out, onProgress)
                OutputFormat.DOCX -> toDocx(inputs, work, out, onProgress)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun toPdf(inputs: List<PickedFile>, work: File, out: File, onProgress: (Float, String) -> Unit) {
        val parts = ArrayList<Pair<File, String>>()
        inputs.forEachIndexed { i, f ->
            onProgress(i.toFloat() / inputs.size * 0.8f, "Preparing ${f.name}")
            val part = File(work, "part$i.pdf")
            when (f.kind()) {
                InputKind.PDF -> Files.copyToFile(ctx, f.uri, part)
                InputKind.IMAGE -> {
                    val img = File(work, "img$i.jpg")
                    val bmp = ImageProcessor.decodeUri(ctx, f.uri, 3000) ?: throw ToolException("\"${f.name}\" couldn't be opened")
                    ImageProcessor.saveJpeg(flatten(bmp), img, 90)
                    PdfTools.imagesToPdf(listOf(img), part, PageSize.A4, ExportQuality.HIGH)
                }
                InputKind.DOCX -> TextPdf.render(readDocx(f), part)
                InputKind.TEXT -> TextPdf.render(readText(f), part)
                InputKind.UNSUPPORTED -> Unit
            }
            parts.add(part to f.name)
        }
        onProgress(0.85f, "Joining pages…")
        PdfTools.merge(parts, out)
        onProgress(1f, "Done")
    }

    private fun toDocx(inputs: List<PickedFile>, work: File, out: File, onProgress: (Float, String) -> Unit) {
        val doc = DocxBuilder()
        inputs.forEachIndexed { i, f ->
            onProgress(i.toFloat() / inputs.size * 0.9f, "Adding ${f.name}")
            if (i > 0 && !doc.isEmpty) doc.pageBreak()
            when (f.kind()) {
                InputKind.PDF -> {
                    val local = Files.copyToFile(ctx, f.uri, File(work, "in$i.pdf"))
                    PdfTools.renderPages(local, 150, 1800) { p, _, bmp, _, _ ->
                        if (p > 0) doc.pageBreak()
                        doc.image(jpeg(bmp, 82), bmp.width, bmp.height)
                    }
                }
                InputKind.IMAGE -> {
                    val bmp = ImageProcessor.decodeUri(ctx, f.uri, 2400) ?: throw ToolException("\"${f.name}\" couldn't be opened")
                    val flat = flatten(bmp)
                    doc.image(jpeg(flat, 88), flat.width, flat.height)
                    flat.recycle()
                }
                InputKind.DOCX -> readDocx(f).forEach { doc.add(it) }
                InputKind.TEXT -> readText(f).forEach { doc.add(it) }
                InputKind.UNSUPPORTED -> Unit
            }
        }
        onProgress(0.95f, "Writing document…")
        doc.write(out)
        onProgress(1f, "Done")
    }

    private fun flatten(bmp: Bitmap): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(bmp, 0f, 0f, null)
        }
        bmp.recycle()
        return out
    }

    private fun jpeg(bmp: Bitmap, q: Int): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    private fun readDocx(f: PickedFile): List<DocBlock> {
        val input = ctx.contentResolver.openInputStream(f.uri) ?: throw ToolException("\"${f.name}\" couldn't be opened")
        return try {
            input.use { s ->
                DocxReader.read(s) { bytes ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
                }
            }
        } catch (e: ToolException) {
            throw e
        } catch (e: Exception) {
            throw ToolException("\"${f.name}\" isn't a readable Word document")
        }
    }

    private fun readText(f: PickedFile): List<DocBlock> {
        val input = ctx.contentResolver.openInputStream(f.uri) ?: throw ToolException("\"${f.name}\" couldn't be opened")
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r\n", "\n")
        val blocks = ArrayList<DocBlock>()
        blocks.add(DocBlock.Heading(f.name.substringBeforeLast('.'), 2))
        text.split(Regex("\n{2,}")).forEach { para -> blocks.add(DocBlock.Paragraph(para.trimEnd())) }
        return blocks
    }
}
