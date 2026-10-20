package com.mithun.latchscan.tools

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.mithun.latchscan.scanner.ImageProcessor
import java.io.File
import kotlin.math.min

/**
 * Lays out headings, paragraphs and pictures onto A4 pages. Uses Android's own text engine,
 * so every script (Kannada, Hindi, emoji…) renders correctly.
 */
object TextPdf {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 56f

    fun render(blocks: List<DocBlock>, out: File) {
        val pdf = PdfDocument()
        try {
            val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(25, 25, 25) }
            val headings = listOf(18f, 15f, 13f).map { size ->
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    color = Color.BLACK
                }
            }
            val contentW = (PAGE_W - 2 * MARGIN).toInt()
            val bottom = PAGE_H - MARGIN
            var pageNo = 0
            var page: PdfDocument.Page? = null
            var y = MARGIN

            fun newPage(): Canvas {
                page?.let { pdf.finishPage(it) }
                pageNo++
                val p = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                page = p
                y = MARGIN
                return p.canvas
            }

            var canvas = newPage()

            fun drawText(text: String, paint: TextPaint, spaceBefore: Float, spaceAfter: Float) {
                if (y > MARGIN) y += spaceBefore
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.15f)
                    .setIncludePad(false)
                    .build()
                var line = 0
                while (line < layout.lineCount) {
                    if (y + (layout.getLineBottom(line) - layout.getLineTop(line)) > bottom) canvas = newPage()
                    // Draw as many lines as fit on this page.
                    val startTop = layout.getLineTop(line)
                    var last = line
                    while (last + 1 < layout.lineCount && y + (layout.getLineBottom(last + 1) - startTop) <= bottom) last++
                    val sliceBottom = layout.getLineBottom(last)
                    canvas.save()
                    canvas.translate(MARGIN, y - startTop)
                    canvas.clipRect(0f, startTop.toFloat(), contentW.toFloat(), sliceBottom.toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                    y += (sliceBottom - startTop)
                    line = last + 1
                }
                y += spaceAfter
            }

            for (block in blocks) {
                when (block) {
                    is DocBlock.Heading -> {
                        if (y > bottom - 60) canvas = newPage()
                        drawText(block.text, headings[(block.level - 1).coerceIn(0, 2)], 10f, 6f)
                    }
                    is DocBlock.Paragraph -> {
                        if (block.text.isBlank()) y += 8f else drawText(block.text, body, 0f, 7f)
                    }
                    is DocBlock.Image -> {
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = ImageProcessor.sampleSize(block.widthPx, block.heightPx, 1800)
                        }
                        val bmp = BitmapFactory.decodeByteArray(block.bytes, 0, block.bytes.size, opts) ?: continue
                        val maxH = bottom - MARGIN
                        val s = min(contentW.toFloat() / bmp.width, maxH / bmp.height).coerceAtMost(1.5f)
                        val w = bmp.width * s
                        val h = bmp.height * s
                        if (y + h > bottom) canvas = newPage()
                        val left = MARGIN + (contentW - w) / 2f
                        canvas.drawBitmap(bmp, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                        y += h + 10f
                        bmp.recycle()
                    }
                    DocBlock.PageBreak -> if (y > MARGIN) canvas = newPage()
                }
            }
            page?.let { pdf.finishPage(it) }
            out.parentFile?.mkdirs()
            out.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }
}
