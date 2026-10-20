package com.mithun.latchscan.util

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object Mime {
    const val PDF = "application/pdf"
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val JPEG = "image/jpeg"
    const val WEBP = "image/webp"
    const val PNG = "image/png"
    const val TXT = "text/plain"
}

/** Metadata of a file the user picked with the system file picker. */
data class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String)

object Files {

    fun exportsDir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun workDir(ctx: Context): File = File(ctx.cacheDir, "work/" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }

    /** Removes temporary work from earlier runs. */
    fun cleanCache(ctx: Context) {
        File(ctx.cacheDir, "work").deleteRecursively()
        exportsDir(ctx).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.deleteRecursively() }
    }

    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim()
        return cleaned.ifEmpty { "Latch Scan" }.take(80)
    }

    /** A fresh file in the exports folder (shareable through the FileProvider). */
    fun exportFile(ctx: Context, baseName: String, ext: String): File {
        val dir = File(exportsDir(ctx), UUID.randomUUID().toString().take(6)).apply { mkdirs() }
        return File(dir, safeName(baseName) + "." + ext)
    }

    fun describe(ctx: Context, uri: Uri): PickedFile {
        var name = "file"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        var mime = ctx.contentResolver.getType(uri) ?: ""
        if (mime.isEmpty() || mime == "application/octet-stream") {
            val ext = name.substringAfterLast('.', "").lowercase()
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
                "docx" -> Mime.DOCX
                "pdf" -> Mime.PDF
                "txt", "md" -> Mime.TXT
                else -> "application/octet-stream"
            }
        }
        return PickedFile(uri, name, size, mime)
    }

    fun copyToFile(ctx: Context, uri: Uri, dest: File): File {
        dest.parentFile?.mkdirs()
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't read the selected file")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        return dest
    }

    fun uriFor(ctx: Context, file: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)

    fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> Mime.PDF
        "docx" -> Mime.DOCX
        "jpg", "jpeg" -> Mime.JPEG
        "webp" -> Mime.WEBP
        "png" -> Mime.PNG
        else -> "application/octet-stream"
    }

    fun share(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(ctx, it) })
        val mime = files.map { mimeFor(it) }.distinct().let { if (it.size == 1) it[0] else "*/*" }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }.apply {
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(ctx: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(ctx, file), mimeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app found to open ${file.extension.uppercase()} files", Toast.LENGTH_SHORT).show()
        }
    }

    /** True when files can be written to Downloads without asking for a permission. */
    val canQuickSave: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Copies [file] into Downloads/Latch Scan. Android 10+ only (see [canQuickSave]). */
    fun saveToDownloads(ctx: Context, file: File): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IllegalStateException("Use Save as on this Android version")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Latch Scan")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create the file in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Couldn't write to Downloads")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return "Download/Latch Scan/${file.name}"
    }

    /** Writes [file] to a location the user chose with the system "Save as" dialog. */
    fun writeTo(ctx: Context, file: File, target: Uri) {
        ctx.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Couldn't write the file")
    }
}
