package com.mithun.latchscan

import com.mithun.latchscan.tools.DocBlock
import com.mithun.latchscan.tools.DocxBuilder
import com.mithun.latchscan.tools.DocxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class DocxTest {

    // The writer/reader treat image bytes as opaque; sizes come from the callback.
    private fun jpeg(w: Int, h: Int): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + "${w}x$h".toByteArray()

    @Test
    fun writesAndReadsBackTextImagesAndBreaks() {
        val out = File.createTempFile("latch", ".docx")
        DocxBuilder()
            .heading("Title <&>")
            .paragraph("One\nTwo\tTab")
            .image(jpeg(300, 200), 300, 200)
            .pageBreak()
            .paragraph("ಕನ್ನಡ ✓")
            .write(out)

        ZipFile(out).use { zip ->
            listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels", "word/media/image1.jpeg")
                .forEach { assertTrue("missing $it", zip.getEntry(it) != null) }
        }

        val blocks = DocxReader.read(out.inputStream()) { b ->
            val (w, h) = String(b, 2, b.size - 2).split('x').map { it.toInt() }
            w to h
        }
        assertEquals(DocBlock.Heading("Title <&>", 1), blocks[0])
        assertEquals(DocBlock.Paragraph("One\nTwo\tTab"), blocks[1])
        val img = blocks[2] as DocBlock.Image
        assertEquals(300, img.widthPx)
        assertEquals(200, img.heightPx)
        assertEquals(DocBlock.PageBreak, blocks[3])
        assertEquals(DocBlock.Paragraph("ಕನ್ನಡ ✓"), blocks[4])
        out.delete()
    }

    @Test
    fun escapesIllegalXmlCharacters() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e", DocxBuilder.esc("a&b<c>d\"e\u0001"))
    }
}
