package com.mithun.latchscan.tools

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min

/** Content pulled out of (or written into) a Word document. */
sealed class DocBlock {
    data class Heading(val text: String, val level: Int = 1) : DocBlock()
    data class Paragraph(val text: String) : DocBlock()
    /** A JPEG or PNG image with its pixel size. */
    class Image(val bytes: ByteArray, val widthPx: Int, val heightPx: Int, val png: Boolean = false) : DocBlock()
    object PageBreak : DocBlock()
}

/**
 * Writes a minimal but fully valid .docx (opens in Word, Google Docs, WPS, LibreOffice).
 * Pure JVM code — no Android dependencies — so it is unit-tested.
 */
class DocxBuilder {

    private val body = StringBuilder()
    private val media = ArrayList<Pair<String, ByteArray>>()
    private var imageCount = 0

    // A4 with 2 cm margins.
    private val maxWidthEmu = 6_120_000L
    private val maxHeightEmu = 8_700_000L

    fun add(block: DocBlock): DocxBuilder {
        when (block) {
            is DocBlock.Heading -> heading(block.text, block.level)
            is DocBlock.Paragraph -> paragraph(block.text)
            is DocBlock.Image -> image(block.bytes, block.widthPx, block.heightPx, block.png)
            DocBlock.PageBreak -> pageBreak()
        }
        return this
    }

    fun heading(text: String, level: Int = 1): DocxBuilder {
        val style = "Heading" + level.coerceIn(1, 3)
        body.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
        runs(text)
        body.append("</w:p>")
        return this
    }

    fun paragraph(text: String): DocxBuilder {
        body.append("<w:p>")
        runs(text)
        body.append("</w:p>")
        return this
    }

    private fun runs(text: String) {
        val lines = text.split('\n')
        body.append("<w:r>")
        lines.forEachIndexed { i, line ->
            if (i > 0) body.append("<w:br/>")
            val parts = line.split('\t')
            parts.forEachIndexed { j, part ->
                if (j > 0) body.append("<w:tab/>")
                if (part.isNotEmpty()) body.append("<w:t xml:space=\"preserve\">").append(esc(part)).append("</w:t>")
            }
        }
        body.append("</w:r>")
    }

    fun pageBreak(): DocxBuilder {
        body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        return this
    }

    fun image(bytes: ByteArray, widthPx: Int, heightPx: Int, png: Boolean = false): DocxBuilder {
        if (widthPx <= 0 || heightPx <= 0) return this
        imageCount++
        val n = imageCount
        val ext = if (png) "png" else "jpeg"
        val fileName = "image$n.$ext"
        media.add(fileName to bytes)
        val rid = "rIdImg$n"
        // Fit inside the printable area keeping the aspect ratio.
        val scale = min(maxWidthEmu.toDouble() / widthPx, maxHeightEmu.toDouble() / heightPx)
        val cx = (widthPx * scale).toLong()
        val cy = (heightPx * scale).toLong()
        body.append(
            "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
                "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
                "<wp:docPr id=\"$n\" name=\"Picture $n\"/>" +
                "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
                "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"$fileName\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>",
        )
        return this
    }

    val isEmpty: Boolean get() = body.isEmpty()

    fun write(out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, text: String) = put(zip, name, text.toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", contentTypes())
            put("_rels/.rels", ROOT_RELS)
            put("word/document.xml", document())
            put("word/styles.xml", STYLES)
            put("word/_rels/document.xml.rels", documentRels())
            media.forEach { (name, bytes) -> put(zip, "word/media/$name", bytes) }
        }
    }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun contentTypes() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "</Types>"

    private fun documentRels(): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sb.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        media.forEachIndexed { i, (name, _) ->
            sb.append("<Relationship Id=\"rIdImg${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$name\"/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun document() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
            "<w:body>" + body +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"

    companion object {
        fun esc(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when {
                    c == '&' -> sb.append("&amp;")
                    c == '<' -> sb.append("&lt;")
                    c == '>' -> sb.append("&gt;")
                    c == '"' -> sb.append("&quot;")
                    // Strip control characters that are illegal in XML 1.0.
                    c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> {}
                    c == '￾' || c == '￿' -> {}
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private const val ROOT_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>"

        private const val STYLES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault>" +
                "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
                "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
                "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr></w:style>" +
                "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"100\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/></w:rPr></w:style>" +
                "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"160\" w:after=\"80\"/><w:outlineLvl w:val=\"2\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr></w:style>" +
                "</w:styles>"
    }
}

/**
 * Reads text, headings and pictures from a .docx in document order.
 * Tables are read cell by cell as plain paragraphs. Pure JVM code (DOM parser).
 */
object DocxReader {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val V = "urn:schemas-microsoft-com:vml"

    /** [imageSize] returns (width, height) in pixels for image bytes, or null if unreadable. */
    fun read(input: InputStream, imageSize: (ByteArray) -> Pair<Int, Int>?): List<DocBlock> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name
                if (name == "word/document.xml" || name == "word/_rels/document.xml.rels" || name.startsWith("word/media/")) {
                    entries[name] = zip.readBytes()
                }
                e = zip.nextEntry
            }
        }
        val docXml = entries["word/document.xml"] ?: throw IllegalArgumentException("Not a Word (.docx) document")
        val rels = HashMap<String, String>()
        entries["word/_rels/document.xml.rels"]?.let { bytes ->
            val d = parse(bytes)
            val list = d.getElementsByTagNameNS("*", "Relationship")
            for (i in 0 until list.length) {
                val el = list.item(i) as Element
                rels[el.getAttribute("Id")] = el.getAttribute("Target")
            }
        }
        val doc = parse(docXml)
        val bodies = doc.getElementsByTagNameNS(W, "body")
        if (bodies.length == 0) return emptyList()
        val out = ArrayList<DocBlock>()
        walk(bodies.item(0), out, rels, entries, imageSize)
        return out
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Hardening: never resolve external entities from untrusted files.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { isExpandEntityReferences = false }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun walk(node: Node, out: MutableList<DocBlock>, rels: Map<String, String>, media: Map<String, ByteArray>, imageSize: (ByteArray) -> Pair<Int, Int>?) {
        var child = node.firstChild
        while (child != null) {
            if (child is Element && child.namespaceURI == W && child.localName == "p") {
                paragraph(child, out, rels, media, imageSize)
            } else if (child is Element) {
                walk(child, out, rels, media, imageSize) // tables, sdt, etc.
            }
            child = child.nextSibling
        }
    }

    private fun paragraph(p: Element, out: MutableList<DocBlock>, rels: Map<String, String>, media: Map<String, ByteArray>, imageSize: (ByteArray) -> Pair<Int, Int>?) {
        val text = StringBuilder()
        var headingLevel = 0
        val images = ArrayList<DocBlock.Image>()
        var pageBreakBefore = false

        fun visit(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) {
                    val ns = c.namespaceURI
                    val ln = c.localName
                    when {
                        ns == W && ln == "pStyle" -> {
                            val v = c.getAttributeNS(W, "val").lowercase()
                            headingLevel = when {
                                v == "title" -> 1
                                v.startsWith("heading") -> v.removePrefix("heading").trim().toIntOrNull()?.coerceIn(1, 3) ?: 1
                                else -> headingLevel
                            }
                        }
                        ns == W && ln == "t" -> text.append(c.textContent)
                        ns == W && ln == "tab" && c.parentNode?.localName == "r" -> text.append('\t')
                        ns == W && (ln == "br" || ln == "cr") -> {
                            if (c.getAttributeNS(W, "type") == "page") pageBreakBefore = true else text.append('\n')
                        }
                        ns == A && ln == "blip" -> {
                            val id = c.getAttributeNS(R, "embed")
                            image(id, rels, media, imageSize)?.let { images.add(it) }
                        }
                        ns == V && ln == "imagedata" -> {
                            val id = c.getAttributeNS(R, "id")
                            image(id, rels, media, imageSize)?.let { images.add(it) }
                        }
                        else -> visit(c)
                    }
                }
                c = c.nextSibling
            }
        }
        visit(p)

        if (pageBreakBefore && out.isNotEmpty() && out.last() != DocBlock.PageBreak) out.add(DocBlock.PageBreak)
        val t = text.toString().trimEnd()
        if (t.isNotEmpty()) {
            out.add(if (headingLevel > 0) DocBlock.Heading(t, headingLevel) else DocBlock.Paragraph(t))
        } else if (images.isEmpty() && !pageBreakBefore) {
            out.add(DocBlock.Paragraph("")) // keep blank lines as spacing
        }
        out.addAll(images)
    }

    private fun image(id: String?, rels: Map<String, String>, media: Map<String, ByteArray>, imageSize: (ByteArray) -> Pair<Int, Int>?): DocBlock.Image? {
        if (id.isNullOrEmpty()) return null
        val target = rels[id] ?: return null
        val path = if (target.startsWith("/")) target.removePrefix("/") else "word/" + target.removePrefix("./")
        val bytes = media[path] ?: return null
        val lower = path.lowercase()
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) return null
        val (w, h) = imageSize(bytes) ?: return null
        return DocBlock.Image(bytes, w, h, png = lower.endsWith(".png"))
    }
}
