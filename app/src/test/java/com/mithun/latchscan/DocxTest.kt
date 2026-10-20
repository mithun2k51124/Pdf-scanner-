package com.mithun.latchscan

import com.mithun.latchscan.tools.DocBlock
import com.mithun.latchscan.tools.DocxBuilder
import com.mithun.latchscan.tools.DocxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class DocxTest {

    private fun jpeg(w: Int, h: Int): ByteArray {
        val bo = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpeg", bo)
        return bo.toByteArray()
    }

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

        val blocks = DocxReader.read(out.inputStream()) { b -> ImageIO.read(b.inputStream())?.let { it.width to it.height } }
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
