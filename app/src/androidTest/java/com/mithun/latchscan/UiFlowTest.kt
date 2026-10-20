package com.mithun.latchscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.mithun.latchscan.scanner.EdgeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks through every screen like a user would and saves a screenshot of each
 * (saved as PNGs in files/screens) so the UI can be reviewed from CI.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule(order = 0)
    val camera: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val screensDir: File by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens").apply { mkdirs() }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(700) // let animations settle
        val bmp: Bitmap? = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        bmp?.let { b -> File(screensDir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun keyBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        Thread.sleep(500)
    }

    private fun waitForText(text: String, timeout: Long = 10_000) {
        rule.waitUntil(timeout) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun seedDocument(): String = runBlocking {
        val app = LatchApp.instance
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val bmp = testCtx.assets.open("paper_dark_table.jpg").use { BitmapFactory.decodeStream(it) }
        val bmp2 = testCtx.assets.open("paper_light_table.jpg").use { BitmapFactory.decodeStream(it) }
        withContext(Dispatchers.Main) { app.session.start() }
        withContext(Dispatchers.Main) { app.session.addCapture(bmp, EdgeDetector.detect(bmp)) }
        withContext(Dispatchers.Main) { app.session.addCapture(bmp2, EdgeDetector.detect(bmp2)) }
        val doc = app.repository.saveNew("Physics assignment", app.session.finalPages(), app.session.dir)
        withContext(Dispatchers.Main) { app.session.clear() }
        doc.id
    }

    @Test
    fun walkThroughEveryScreen() {
        try {
            walk()
        } catch (t: Throwable) {
            shot("zz_failure")
            throw t
        }
    }

    private fun walk() {
        // Start from a clean library.
        runBlocking { LatchApp.instance.repository.docs.value.forEach { LatchApp.instance.deleteDocument(it) } }
        rule.waitForIdle()
        shot("01_home_empty")

        rule.onNodeWithText("Tools").performClick()
        shot("02_tools")

        val tools = listOf(
            Triple("Merge PDFs", "Add files to begin", "03_merge_pdfs"),
            Triple("Merge any documents", "Merge documents", "04_merge_docs"),
            Triple("Compress PDF", "Shrink a PDF to the size you need", "05_compress_pdf"),
            Triple("Compress image", "Fit photos under an upload limit", "06_compress_image"),
        )
        for ((label, title, file) in tools) {
            rule.onNodeWithText(label).performClick()
            waitForText(title)
            shot(file)
            back()
            waitForText("Merge PDFs")
        }

        // Library with a document
        seedDocument()
        rule.onNodeWithText("Scans").performClick()
        waitForText("Physics assignment")
        shot("07_home_with_scan")

        rule.onNodeWithText("Physics assignment").performClick()
        waitForText("Share PDF")
        shot("08_document")

        rule.onNodeWithText("Change").performClick()
        waitForText("PDF settings")
        shot("09_pdf_settings")
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()

        rule.onAllNodes(hasContentDescription("Rename")).onFirst().performClick()
        shot("10_rename_dialog")
        rule.onNodeWithText("Cancel").performClick()

        // Page editor
        rule.onNodeWithText("1").performClick()
        waitForText("Magic")
        shot("11_page_editor")
        rule.onNodeWithText("Grayscale").performClick()
        Thread.sleep(2500)
        shot("12_page_editor_gray")
        rule.onNodeWithText("Arrange").performClick()
        waitForText("Arrange pages")
        shot("13_arrange")
        keyBack() // a real key press goes to the sheet's own window and closes it
        rule.waitForIdle()

        rule.onNodeWithText("Crop").performClick()
        waitForText("Adjust corners")
        Thread.sleep(1200)
        shot("14_crop")
        rule.onNodeWithContentDescription("Cancel").performClick()
        waitForText("Magic")
        back() // back to document
        waitForText("Share PDF")
        back() // back to home
        waitForText("Scan a document")

        // Camera
        rule.onNodeWithText("Scan a document").performClick()
        waitForText("Auto")
        Thread.sleep(3000)
        shot("15_camera_auto")
        rule.onNodeWithText("Manual").performClick()
        Thread.sleep(800)
        shot("16_camera_manual")
        rule.onNodeWithText("Front & back").performClick()
        Thread.sleep(800)
        shot("17_camera_front_back")
        rule.onNodeWithContentDescription("Take photo").performClick()
        // Manual capture opens the crop editor.
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("Adjust corners")).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1200)
        shot("18_crop_after_capture")
        rule.onNodeWithText("Done").performClick()
        waitForText("Manual")
        Thread.sleep(1500)
        shot("19_camera_with_page")
        rule.onNodeWithContentDescription("Review 1 pages").performClick()
        waitForText("Review scan")
        Thread.sleep(1500)
        shot("20_review")
        rule.onNodeWithText("Save 1 page(s)").performClick()
        waitForText("Share PDF", 20_000)
        shot("21_saved_document")
    }
}

