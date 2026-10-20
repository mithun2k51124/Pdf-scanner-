package com.mithun.latchscan.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Stores every saved scan as a folder: files/docs/<id>/doc.json plus the page images.
 * Everything stays on the device.
 */
class DocumentRepository(context: Context) {

    private val root = File(context.filesDir, "docs").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _docs = MutableStateFlow<List<ScanDocument>>(emptyList())
    val docs: StateFlow<List<ScanDocument>> = _docs.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            val list = root.listFiles()?.mapNotNull { dir ->
                val f = File(dir, "doc.json")
                if (!f.exists()) return@mapNotNull null
                runCatching { ScanDocument.fromJson(JSONObject(f.readText())) }
                    .onFailure { Log.w("Repo", "Skipping broken document ${dir.name}", it) }
                    .getOrNull()
            }.orEmpty()
            _docs.value = list.sortedByDescending { it.updatedAt }
            _loaded.value = true
        }
    }

    fun dirOf(docId: String): File = File(root, docId)

    fun get(id: String): ScanDocument? = _docs.value.firstOrNull { it.id == id }

    fun processedFile(doc: ScanDocument, page: Page) = File(dirOf(doc.id), page.processed)
    fun originalFile(doc: ScanDocument, page: Page) = File(dirOf(doc.id), page.original)

    private fun write(doc: ScanDocument) {
        val dir = dirOf(doc.id).apply { mkdirs() }
        val tmp = File(dir, "doc.json.tmp")
        tmp.writeText(doc.toJson().toString())
        val target = File(dir, "doc.json")
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun publish(doc: ScanDocument) {
        _docs.update { list -> (listOf(doc) + list.filter { it.id != doc.id }).sortedByDescending { it.updatedAt } }
    }

    /** Moves page files from [fromDir] into the document folder and saves it. */
    suspend fun saveNew(name: String, pages: List<Page>, fromDir: File): ScanDocument = withContext(Dispatchers.IO) {
        val doc = ScanDocument(name = name, pages = pages)
        val dir = dirOf(doc.id).apply { mkdirs() }
        pages.forEach { moveFiles(it, fromDir, dir) }
        write(doc)
        publish(doc)
        doc
    }

    suspend fun appendPages(docId: String, pages: List<Page>, fromDir: File): ScanDocument? = withContext(Dispatchers.IO) {
        val doc = get(docId) ?: return@withContext null
        val dir = dirOf(doc.id)
        pages.forEach { moveFiles(it, fromDir, dir) }
        val updated = doc.copy(pages = doc.pages + pages, updatedAt = System.currentTimeMillis())
        write(updated)
        publish(updated)
        updated
    }

    private fun moveFiles(p: Page, from: File, to: File) {
        for (name in listOf(p.original, p.processed)) {
            val src = File(from, name)
            if (!src.exists()) continue
            val dst = File(to, name)
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
        }
    }

    suspend fun update(doc: ScanDocument): ScanDocument = withContext(Dispatchers.IO) {
        val updated = doc.copy(updatedAt = System.currentTimeMillis())
        write(updated)
        publish(updated)
        // Remove image files no longer referenced by any page.
        val keep = updated.pages.flatMap { listOf(it.original, it.processed) }.toSet() + "doc.json"
        dirOf(doc.id).listFiles()?.forEach { if (it.name !in keep) it.delete() }
        updated
    }

    suspend fun rename(doc: ScanDocument, name: String) = update(doc.copy(name = name.trim().ifEmpty { doc.name }))

    suspend fun delete(doc: ScanDocument) = withContext(Dispatchers.IO) {
        dirOf(doc.id).deleteRecursively()
        _docs.update { list -> list.filter { it.id != doc.id } }
    }
}
