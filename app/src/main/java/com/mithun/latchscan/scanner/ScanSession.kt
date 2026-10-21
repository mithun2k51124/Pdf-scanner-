package com.mithun.latchscan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mithun.latchscan.data.Filter
import com.mithun.latchscan.data.Page
import com.mithun.latchscan.data.Quad
import com.mithun.latchscan.data.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class CaptureMode(val label: String) {
    DOCUMENT("Document"),
    FRONT_BACK("Front & back"),
}

/**
 * Pages captured in the current scan, before they are saved as a document.
 * Lives as long as the app process; files sit in files/session/.
 */
class ScanSession(context: Context) : PageHost {

    val dir: File = File(context.filesDir, "session").apply { mkdirs() }
    private val appContext = context.applicationContext
    private val lock = Mutex()

    override val pages = mutableStateListOf<Page>()

    var mode by mutableStateOf(CaptureMode.DOCUMENT)
    var autoCapture by mutableStateOf(true)
    var combineFrontBack by mutableStateOf(false)

    /** When set, saving appends pages to this existing document instead of creating one. */
    var targetDocId by mutableStateOf<String?>(null)

    /** Number of pages being processed in the background (shows a spinner on the stack). */
    var busy by mutableStateOf(0)

    fun start(targetDoc: String? = null) {
        clear()
        targetDocId = targetDoc
    }

    fun clear() {
        pages.clear()
        dir.listFiles()?.forEach { it.deleteRecursively() }
        targetDocId = null
        combineFrontBack = false
    }

    override fun file(name: String) = File(dir, name)

    /** Which side the next capture is, in front & back mode. */
    fun nextSide(): Side {
        if (mode == CaptureMode.DOCUMENT) return Side.NONE
        val all = pages.map { it.side } + pendingSides
        return if (all.count { it == Side.FRONT } > all.count { it == Side.BACK }) Side.BACK else Side.FRONT
    }

    private val pendingSides = ArrayList<Side>()

    /**
     * Stores a captured photo and produces its cropped/filtered page. Must be called from
     * the main thread (the side and busy counter are claimed before switching threads).
     */
    suspend fun addCapture(bitmap: Bitmap, quad: Quad?, filter: Filter = Filter.ORIGINAL): Page {
        val side = nextSide()
        pendingSides.add(side)
        busy++
        try {
            return addCaptureInternal(bitmap, quad, filter, side)
        } finally {
            pendingSides.remove(side)
            busy--
        }
    }

    private suspend fun addCaptureInternal(bitmap: Bitmap, quad: Quad?, filter: Filter, side: Side): Page = withContext(Dispatchers.IO) {
        run {
            val name = "${UUID.randomUUID().toString().take(12)}_src.jpg"
            ImageProcessor.saveJpeg(bitmap, file(name), 92)
            val page = Page(
                original = name,
                quad = quad?.takeIf { it.isValid() } ?: Quad.FULL,
                filter = filter,
                side = side,
            )
            ImageProcessor.render(file(page.original), page, file(page.processed))
            withContext(Dispatchers.Main) { pages.add(page) }
            page
        }
    }

    /** Imports gallery images; each one is auto-cropped when a page outline is found. */
    suspend fun importImages(uris: List<Uri>): Int {
        var ok = 0
        for (uri in uris) {
            val loaded = withContext(Dispatchers.IO) {
                val bmp = runCatching { ImageProcessor.decodeUri(appContext, uri) }.getOrNull()
                bmp?.let { it to EdgeDetector.detect(it) }
            } ?: continue
            val (bmp, quad) = loaded
            addCapture(bmp, quad, Filter.ORIGINAL)
            bmp.recycle()
            ok++
        }
        return ok
    }

    /** Re-renders a page after its crop, filter or rotation changed. */
    override suspend fun update(page: Page): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val updated = page.copy(rev = System.currentTimeMillis())
            ImageProcessor.render(file(updated.original), updated, file(updated.processed))
            withContext(Dispatchers.Main) {
                val i = pages.indexOfFirst { it.id == page.id }
                if (i >= 0) pages[i] = updated
                Unit
            }
            Unit
        }
    }

    override suspend fun delete(page: Page) {
        pages.removeAll { it.id == page.id }
        file(page.original).delete()
        file(page.processed).delete()
    }

    override suspend fun reorder(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val p = pages.removeAt(from)
        pages.add(to, p)
    }

    /**
     * Pages ready to be saved. In front & back mode with "one sheet" enabled, each
     * front/back pair is merged into a single A4 page.
     */
    suspend fun finalPages(): List<Page> = withContext(Dispatchers.IO) {
        val list = pages.toList()
        if (mode != CaptureMode.FRONT_BACK || !combineFrontBack) return@withContext list
        val result = ArrayList<Page>()
        var i = 0
        while (i < list.size) {
            val a = list[i]
            val b = list.getOrNull(i + 1)
            if (a.side == Side.FRONT && b != null && b.side == Side.BACK) {
                val name = "${UUID.randomUUID().toString().take(12)}_pair.jpg"
                if (ImageProcessor.combineFrontBack(file(a.processed), file(b.processed), file(name))) {
                    val combined = Page(original = name, quad = Quad.FULL, filter = Filter.ORIGINAL, side = Side.NONE)
                    file(name).copyTo(file(combined.processed), overwrite = true)
                    result.add(combined)
                    i += 2
                    continue
                }
            }
            result.add(a)
            i++
        }
        result
    }
}
