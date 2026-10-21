package com.mithun.latchscan

import android.app.Application
import android.util.Log
import com.mithun.latchscan.data.DocumentRepository
import com.mithun.latchscan.scanner.DocumentPageHost
import com.mithun.latchscan.scanner.ScanSession
import com.mithun.latchscan.util.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class LatchApp : Application() {

    lateinit var repository: DocumentRepository
        private set

    /** The scan currently being captured (camera -> crop -> review). */
    lateinit var session: ScanSession
        private set

    var openCvReady: Boolean = false
        private set

    /** For work that must finish even if the screen that started it closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val docHosts = HashMap<String, DocumentPageHost>()

    /** One shared page editor per saved document, re-synced from storage on request. */
    fun docHost(docId: String): DocumentPageHost? {
        val doc = repository.get(docId) ?: return null
        return docHosts.getOrPut(docId) { DocumentPageHost(repository, doc) }
    }

    suspend fun deleteDocument(doc: com.mithun.latchscan.data.ScanDocument) {
        docHosts.remove(doc.id)
        repository.delete(doc)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            Log.e("LatchApp", "OpenCV failed to load", t)
            false
        }
        PDFBoxResourceLoader.init(applicationContext)
        repository = DocumentRepository(this)
        session = ScanSession(this)
        Files.cleanCache(this)
        // Warm up the corner model so the first camera frames are fast.
        appScope.launch(Dispatchers.Default) { com.mithun.latchscan.scanner.CornerModel.get() }
    }

    companion object {
        lateinit var instance: LatchApp
            private set
    }
}
