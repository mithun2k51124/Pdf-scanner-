package com.mithun.latchscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mithun.latchscan.data.Filter
import com.mithun.latchscan.data.Pt
import com.mithun.latchscan.data.Quad
import com.mithun.latchscan.scanner.EdgeDetector
import com.mithun.latchscan.scanner.ImageProcessor
import com.mithun.latchscan.tools.DocxBuilder
import com.mithun.latchscan.tools.ExportQuality
import com.mithun.latchscan.tools.Exporter
import com.mithun.latchscan.tools.ImageFormat
import com.mithun.latchscan.tools.ImageTools
import com.mithun.latchscan.tools.MergeEngine
import com.mithun.latchscan.tools.OutputFormat
import com.mithun.latchscan.tools.PageSize
import com.mithun.latchscan.tools.PdfTools
import com.mithun.latchscan.tools.ToolException
import com.mithun.latchscan.util.Mime
import com.mithun.latchscan.util.PickedFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import kotlin.random.Random

/** Runs the real scanning / PDF engine on a device (native OpenCV, PdfBox, PdfRenderer). */
@RunWith(AndroidJUnit4::class)
class EngineTest {

    private lateinit var ctx: Context
    private lateinit var testCtx: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        testCtx = InstrumentationRegistry.getInstrumentation().context
        dir = File(ctx.cacheDir, "engine-test").apply { deleteRecursively(); mkdirs() }
        assertTrue("OpenCV native library failed to load", LatchApp.instance.openCvReady)
    }

    private fun asset(name: String): Bitmap =
        testCtx.assets.open(name).use { BitmapFactory.decodeStream(it) }

    private fun assetFile(name: String): File {
        val f = File(dir, name)
        testCtx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /** A photo-like image that JPEG can't squeeze easily. */
    private fun noisyJpeg(name: String, w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val r = Random(42)
        val px = IntArray(w * h) { Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f
    }

    private fun assertNear(expected: Pt, actual: Pt, tol: Float = 0.03f) {
        assertTrue("expected $expected got $actual", expected.dist(actual) < tol)
    }

    @Test
    fun detectsPaperOnDarkAndLightTables() {
        // Ground truth from the generated test scene (1280x960).
        val truth = listOf(Pt(380 / 1280f, 120 / 960f), Pt(930 / 1280f, 170 / 960f), Pt(1010 / 1280f, 860 / 960f), Pt(290 / 1280f, 820 / 960f))
        for (name in listOf("paper_dark_table.jpg", "paper_light_table.jpg")) {
            val q = EdgeDetector.detect(asset(name))
            assertNotNull("no page found in $name", q)
            q!!.points.zip(truth).forEach { (a, e) -> assertNear(e, a) }
        }
    }

    @Test
    fun detectsPaperOnWoodWithPenAndShadow() {
        fun pts(vararg v: Int) = v.toList().chunked(2).map { (x, y) -> Pt(x / 1280f, y / 960f) }
        val cases = mapOf(
            "paper_wood_pen.jpg" to pts(400, 140, 900, 190, 960, 880, 320, 830),
            "paper_wood_tilted_shadow.jpg" to pts(300, 220, 930, 110, 1050, 760, 380, 900),
        )
        for ((name, truth) in cases) {
            val q = EdgeDetector.detect(asset(name))
            assertNotNull("no page found in $name", q)
            q!!.points.zip(truth).forEach { (a, e) -> assertNear(e, a) }
        }
    }

    @Test
    fun cornerModelLoadsAndRuns() {
        val model = com.mithun.latchscan.scanner.CornerModel.get()
        assertNotNull("corner model failed to load", model)
        val bgr = org.opencv.core.Mat()
        org.opencv.android.Utils.bitmapToMat(asset("white_on_white_blur.jpg"), bgr)
        org.opencv.imgproc.Imgproc.cvtColor(bgr, bgr, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR)
        val r = model!!.predict(bgr)
        assertNotNull(r)
        assertEquals(4, r!!.corners.size)
        bgr.release()
    }

    @Test
    fun detectsHardPagesWithTheCornerModel() {
        fun pts(vararg v: Int) = v.toList().chunked(2).map { (x, y) -> Pt(x / 1280f, y / 960f) }
        val cases = listOf(
            // white paper on a white table, motion blur and sensor noise
            Triple("white_on_white_blur.jpg", pts(330, 120, 930, 150, 960, 880, 300, 860), 0.03f),
            // page nearly filling the frame
            Triple("paper_fills_frame.jpg", pts(30, 25, 1245, 35, 1255, 935, 22, 925), 0.03f),
            // open book: the right-hand page (its outer edge may land on the cover edge)
            Triple("book_spread_right_page.jpg", pts(600, 95, 1150, 80, 1170, 880, 610, 890), 0.07f),
            // close-up page on a white table with a heavy shadow: just needs a usable outline
            Triple("page_white_table_close.jpg", pts(150, 40, 1120, 60, 1150, 930, 120, 910), 0.15f),
        )
        for ((name, truth, tol) in cases) {
            val q = EdgeDetector.detect(asset(name))
            assertNotNull("no page found in $name", q)
            q!!.points.zip(truth).forEach { (a, e) -> assertTrue("$name expected $e got $a", e.dist(a) < tol) }
        }
    }

    @Test
    fun findsNothingInAnEmptyScene() {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        assertEquals(null, EdgeDetector.detect(bmp))
        assertEquals(null, EdgeDetector.detect(asset("wood_no_paper.jpg")))
    }

    @Test
    fun warpsAndFiltersAPage() {
        val src = asset("paper_dark_table.jpg")
        val q = EdgeDetector.detect(src)!!
        val warped = ImageProcessor.warp(src, q)
        // The corrected page keeps roughly the size of the paper in the photo.
        assertTrue(warped.width in 500..800 && warped.height in 600..800)
        for (f in Filter.entries) {
            val out = ImageProcessor.applyFilter(warped, f)
            assertEquals(warped.width, out.width)
            // Centre-left margin of the page should be near white after Magic/B&W.
            if (f == Filter.ENHANCED || f == Filter.BW) {
                val c = out.getPixel(out.width / 30, out.height / 2)
                assertTrue("$f margin not white: ${Integer.toHexString(c)}", Color.red(c) > 200)
            }
        }
        val rotated = ImageProcessor.rotate(warped.copy(Bitmap.Config.ARGB_8888, false), 90)
        assertEquals(warped.height, rotated.width)
    }

    @Test
    fun buildsMergesAndCountsPdfs() {
        val img = assetFile("paper_dark_table.jpg")
        val a = File(dir, "a.pdf")
        val b = File(dir, "b.pdf")
        PdfTools.imagesToPdf(listOf(img, img), a, PageSize.A4, ExportQuality.BALANCED)
        PdfTools.imagesToPdf(listOf(img), b, PageSize.AUTO, ExportQuality.SMALL)
        assertEquals(2, PdfTools.pageCount(a))
        assertEquals(1, PdfTools.pageCount(b))
        val merged = File(dir, "merged.pdf")
        PdfTools.merge(listOf(a to "a.pdf", b to "b.pdf", a to "a.pdf"), merged)
        assertEquals(5, PdfTools.pageCount(merged))
    }

    @Test
    fun rejectsNonPdfInput() {
        val bogus = File(dir, "bogus.pdf").apply { writeText("not a pdf") }
        try {
            PdfTools.merge(listOf(bogus to "bogus.pdf"), File(dir, "x.pdf"))
            fail("should have thrown")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("bogus.pdf"))
        }
    }

    @Test
    fun compressesPdfUnderTarget() {
        val pages = (1..4).map { noisyJpeg("n$it.jpg", 1600, 2200) }
        val big = File(dir, "big.pdf")
        PdfTools.imagesToPdf(pages, big, PageSize.A4, ExportQuality.HIGH)
        assertTrue("test PDF should be large, was ${big.length()}", big.length() > 2_000_000)
        val target = 300 * 1024L
        val out = File(dir, "small.pdf")
        val r = PdfTools.compressToSize(big, target, out) { _, _ -> }
        assertTrue("got ${r.size}", r.reachedTarget)
        assertTrue(out.length() <= target)
        assertEquals(4, PdfTools.pageCount(out))
    }

    @Test
    fun compressesImageUnderTarget() {
        val src = noisyJpeg("photo.jpg", 2400, 1800)
        for (fmt in ImageFormat.entries) {
            val out = File(dir, "photo-small.${fmt.ext}")
            val r = ImageTools.compressToSize(ctx, Uri.fromFile(src), 60 * 1024L, fmt, null, out)
            assertTrue("$fmt got ${r.size}", r.reachedTarget && out.length() <= 60 * 1024L)
            assertNotNull(BitmapFactory.decodeFile(out.absolutePath))
        }
    }

    @Test
    fun mergesMixedDocumentsToPdfAndDocx() {
        val pdf = File(dir, "in.pdf")
        PdfTools.imagesToPdf(listOf(assetFile("paper_light_table.jpg")), pdf, PageSize.A4, ExportQuality.SMALL)
        val img = assetFile("paper_dark_table.jpg")
        val docx = File(dir, "notes.docx")
        val longText = (1..120).joinToString(" ") { "Line $it of the assignment notes with ಕನ್ನಡ text." }
        DocxBuilder().heading("Assignment").paragraph(longText).write(docx)
        val txt = File(dir, "readme.txt").apply { writeText("Hello\n\nSecond paragraph") }
        val inputs = listOf(
            PickedFile(Uri.fromFile(pdf), "in.pdf", pdf.length(), Mime.PDF),
            PickedFile(Uri.fromFile(img), "photo.jpg", img.length(), Mime.JPEG),
            PickedFile(Uri.fromFile(docx), "notes.docx", docx.length(), Mime.DOCX),
            PickedFile(Uri.fromFile(txt), "readme.txt", txt.length(), Mime.TXT),
        )
        val outPdf = File(dir, "merged.pdf")
        MergeEngine(ctx).merge(inputs, OutputFormat.PDF, outPdf) { _, _ -> }
        // 1 (pdf) + 1 (image) + >=2 (long docx) + 1 (txt)
        assertTrue("pages: ${PdfTools.pageCount(outPdf)}", PdfTools.pageCount(outPdf) >= 5)

        val outDocx = File(dir, "merged.docx")
        MergeEngine(ctx).merge(inputs, OutputFormat.DOCX, outDocx) { _, _ -> }
        ZipFile(outDocx).use { z ->
            val xml = z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(xml.contains("Assignment"))
            assertTrue(xml.contains("Second paragraph"))
            assertNotNull(z.getEntry("word/media/image2.jpeg"))
        }
    }

    @Test
    fun scanSessionSavesAndExportsADocument() = runBlocking {
        val app = LatchApp.instance
        val session = app.session
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { session.start() }
        val bmp = asset("paper_dark_table.jpg")
        val quad = EdgeDetector.detect(bmp)
        val p1 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { session.addCapture(bmp, quad) }
        val p2 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { session.addCapture(asset("paper_light_table.jpg"), null) }
        assertTrue(session.file(p1.processed).exists())
        assertEquals(Quad.FULL, p2.quad)
        session.update(p2.copy(filter = Filter.BW, rotation = 90))
        val doc = app.repository.saveNew("Engine test", session.finalPages(), session.dir)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { session.clear() }
        assertEquals(2, doc.pages.size)
        val exporter = Exporter(ctx, app.repository)
        assertEquals(2, PdfTools.pageCount(exporter.pdf(doc, PageSize.A4, ExportQuality.BALANCED)))
        assertTrue(exporter.docx(doc).length() > 1000)
        assertEquals(2, exporter.images(doc).size)
        app.deleteDocument(doc)
        assertEquals(null, app.repository.get(doc.id))
    }
}
