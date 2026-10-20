package com.mithun.latchscan.scanner

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.mithun.latchscan.data.DocumentRepository
import com.mithun.latchscan.data.Page
import com.mithun.latchscan.data.ScanDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Something that owns a list of editable pages: the live scan session or a saved document. */
interface PageHost {
    val pages: SnapshotStateList<Page>
    fun file(name: String): File
    suspend fun update(page: Page)
    suspend fun delete(page: Page)
    suspend fun reorder(from: Int, to: Int)
}

/** Edits the pages of a saved document; every change is written straight to storage. */
class DocumentPageHost(private val repo: DocumentRepository, private var doc: ScanDocument) : PageHost {

    override val pages: SnapshotStateList<Page> = mutableStateListOf<Page>().apply { addAll(doc.pages) }
    private val lock = Mutex()

    override fun file(name: String): File = File(repo.dirOf(doc.id), name)

    val docId: String get() = doc.id

    /** Reloads pages after the document was changed elsewhere (e.g. pages appended). */
    fun sync() {
        val latest = repo.get(doc.id) ?: return
        doc = latest
        if (pages.toList() != latest.pages) {
            pages.clear()
            pages.addAll(latest.pages)
        }
    }

    private suspend fun persist() {
        val latest = repo.get(doc.id) ?: doc
        doc = repo.update(latest.copy(pages = pages.toList()))
    }

    override suspend fun update(page: Page): Unit = lock.withLock {
        val updated = page.copy(rev = System.currentTimeMillis())
        withContext(Dispatchers.IO) { ImageProcessor.render(file(updated.original), updated, file(updated.processed)) }
        val i = pages.indexOfFirst { it.id == page.id }
        if (i >= 0) pages[i] = updated
        persist()
    }

    override suspend fun delete(page: Page): Unit = lock.withLock {
        pages.removeAll { it.id == page.id }
        persist()
    }

    override suspend fun reorder(from: Int, to: Int) {
        lock.withLock {
            if (from in pages.indices && to in pages.indices && from != to) {
                pages.add(to, pages.removeAt(from))
                persist()
            }
        }
    }
}
