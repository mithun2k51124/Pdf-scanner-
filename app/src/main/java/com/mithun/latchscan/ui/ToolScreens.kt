package com.mithun.latchscan.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mithun.latchscan.tools.ImageFormat
import com.mithun.latchscan.tools.ImageTools
import com.mithun.latchscan.tools.InputKind
import com.mithun.latchscan.tools.MergeEngine
import com.mithun.latchscan.tools.OutputFormat
import com.mithun.latchscan.tools.PdfTools
import com.mithun.latchscan.tools.kind
import com.mithun.latchscan.ui.theme.Brand
import com.mithun.latchscan.ui.theme.Latch
import com.mithun.latchscan.util.Files
import com.mithun.latchscan.util.Mime
import com.mithun.latchscan.util.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())

/** Shared frame for every tool: header, scrollable body, sticky action button, busy overlay. */
@Composable
private fun ToolFrame(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    busyMessage: String?,
    progress: Float?,
    action: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = busyMessage != null) { /* keep the user here until the work finishes */ }
    Box(
        Modifier
            .fillMaxSize()
            .background(Latch.colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = title, subtitle = subtitle, onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
            Box(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) { action() }
        }
        BusyOverlay(busyMessage != null, busyMessage ?: "", progress)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Latch.colors.text)
}

@Composable
private fun ErrorNote(text: String?) {
    AnimatedVisibility(text != null, enter = fadeIn() + expandVertically()) {
        Text(
            text ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.Danger,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Brand.Danger.copy(alpha = 0.10f))
                .padding(14.dp),
        )
    }
}

private fun iconFor(f: PickedFile): ImageVector = when (f.kind()) {
    InputKind.PDF -> Icons.Rounded.PictureAsPdf
    InputKind.IMAGE -> Icons.Rounded.Image
    InputKind.DOCX -> Icons.Rounded.Description
    InputKind.TEXT -> Icons.Rounded.TextSnippet
    InputKind.UNSUPPORTED -> Icons.Rounded.Description
}

@Composable
private fun FileRow(f: PickedFile, index: Int?, dragging: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (dragging) Brand.Highlighter.copy(alpha = 0.25f) else Latch.colors.surface)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = Latch.colors.textMuted, modifier = Modifier.width(22.dp))
        }
        Icon(iconFor(f), null, tint = if (f.kind() == InputKind.UNSUPPORTED) Brand.Danger else Latch.colors.text)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.titleSmall, color = Latch.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (f.kind() == InputKind.UNSUPPORTED) "Not supported" else if (f.size >= 0) formatBytes(f.size) else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (f.kind() == InputKind.UNSUPPORTED) Brand.Danger else Latch.colors.textMuted,
            )
        }
        if (index != null) Icon(Icons.Rounded.DragHandle, "Long-press and drag to reorder", tint = Latch.colors.textMuted)
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .pressable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, "Remove", tint = Latch.colors.textMuted) }
    }
}

@Composable
private fun AddFilesButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, Latch.colors.outline, RoundedCornerShape(16.dp))
            .pressable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = Latch.colors.text)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Latch.colors.text)
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(80)) },
        label = { Text("Output file name") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Brand.HighlighterDeep,
            focusedLabelColor = Latch.colors.text,
            unfocusedContainerColor = Latch.colors.surface,
            focusedContainerColor = Latch.colors.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Target size presets plus a custom value in KB or MB. Returns bytes or null if invalid. */
@Composable
private fun TargetSizePicker(presetsKb: List<Int>, selectedBytes: Long?, onChange: (Long?) -> Unit) {
    var custom by remember { mutableStateOf("") }
    var unitMb by remember { mutableStateOf(false) }
    var usingCustom by remember { mutableStateOf(false) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presetsKb.forEach { kb ->
            val bytes = kb * 1024L
            Chip(
                if (kb >= 1024) "${kb / 1024} MB" else "$kb KB",
                !usingCustom && selectedBytes == bytes,
                { usingCustom = false; onChange(bytes) },
            )
        }
        Chip("Custom", usingCustom, {
            usingCustom = true
            onChange(parseSize(custom, unitMb))
        })
    }
    AnimatedVisibility(usingCustom) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedTextField(
                value = custom,
                onValueChange = { v ->
                    custom = v.filter { it.isDigit() || it == '.' }.take(7)
                    onChange(parseSize(custom, unitMb))
                },
                label = { Text("Size") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.HighlighterDeep, focusedLabelColor = Latch.colors.text),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Chip("KB", !unitMb, { unitMb = false; onChange(parseSize(custom, false)) })
            Spacer(Modifier.width(6.dp))
            Chip("MB", unitMb, { unitMb = true; onChange(parseSize(custom, true)) })
        }
    }
}

private fun parseSize(text: String, mb: Boolean): Long? {
    val v = text.toDoubleOrNull() ?: return null
    if (v <= 0) return null
    val bytes = (v * 1024 * (if (mb) 1024 else 1)).toLong()
    return bytes.takeIf { it >= 10 * 1024 } // under 10 KB isn't achievable for real documents
}

// ---------------------------------------------------------------------------------------
// Merge PDFs / Merge documents
// ---------------------------------------------------------------------------------------

@Composable
fun MergeScreen(anyDocument: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<PickedFile>() }
    var name by remember { mutableStateOf("Merged ${stamp()}") }
    var format by remember { mutableStateOf(OutputFormat.PDF) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }

    val mimes = if (anyDocument) arrayOf(Mime.PDF, "image/*", Mime.DOCX, "text/plain") else arrayOf(Mime.PDF)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val added = uris.map { Files.describe(context, it) }
        val filtered = if (anyDocument) added else added.filter { it.kind() == InputKind.PDF }
        if (filtered.size < added.size) error = "Only PDF files can be added here" else error = null
        files.addAll(filtered.filter { f -> files.none { it.uri == f.uri } })
        result = null
    }

    val canRun = files.isNotEmpty() && (anyDocument || files.size >= 2) && files.none { it.kind() == InputKind.UNSUPPORTED }

    ToolFrame(
        title = if (anyDocument) "Merge documents" else "Merge PDFs",
        subtitle = if (files.isEmpty()) "Add files to begin" else "${files.size} file${if (files.size == 1) "" else "s"}, merged top to bottom",
        onBack = onBack,
        busyMessage = busy,
        progress = if (progress > 0f) progress else null,
        action = {
            PrimaryButton(
                text = if (anyDocument) "Merge into ${format.label}" else "Merge ${files.size} PDFs",
                enabled = canRun,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    error = null
                    busy = "Merging…"
                    progress = 0f
                    scope.launch {
                        try {
                            val ext = if (anyDocument) format.ext else "pdf"
                            val out = Files.exportFile(context, name, ext)
                            withContext(Dispatchers.IO) {
                                if (anyDocument) {
                                    MergeEngine(context.applicationContext).merge(files.toList(), format, out) { p, msg ->
                                        progress = p
                                        busy = msg
                                    }
                                } else {
                                    val local = files.mapIndexed { i, f ->
                                        progress = i.toFloat() / files.size
                                        Files.copyToFile(context, f.uri, File(Files.workDir(context), "in$i.pdf")) to f.name
                                    }
                                    PdfTools.merge(local, out)
                                }
                            }
                            result = out
                        } catch (t: Throwable) {
                            error = t.message ?: "Merging failed"
                        } finally {
                            busy = null
                        }
                    }
                },
            )
        },
    ) {
        if (anyDocument) {
            Text(
                "Add PDFs, Word (.docx) files, photos and text files. Word and text are laid out as pages; PDF pages are kept as they are.",
                style = MaterialTheme.typography.bodyMedium,
                color = Latch.colors.textMuted,
            )
        }
        if (files.isNotEmpty()) {
            if (files.size > 1) Text("Long-press a file and drag to change the order", style = MaterialTheme.typography.bodySmall, color = Latch.colors.textMuted)
            // Short lists: render inline so the whole screen scrolls together.
            ReorderableList(
                items = files.toList(),
                key = { it.uri.toString() },
                onMove = { from, to -> files.add(to, files.removeAt(from)); result = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .height((files.size * 76).coerceAtMost(420).dp),
            ) { i, f, dragging ->
                FileRow(f, i, dragging) { files.remove(f); result = null }
            }
        }
        AddFilesButton(if (files.isEmpty()) (if (anyDocument) "Choose files" else "Choose PDFs") else "Add more") { picker.launch(mimes) }
        if (anyDocument) {
            SectionLabel("Save as")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputFormat.entries.forEach { f -> Chip(f.label, format == f, { format = f; result = null }) }
            }
            if (format == OutputFormat.DOCX && files.any { it.kind() == InputKind.PDF }) {
                Text("PDF pages are added to the Word file as page images.", style = MaterialTheme.typography.bodySmall, color = Latch.colors.textMuted)
            }
        }
        if (!anyDocument && files.size == 1) {
            Text("Add at least one more PDF to merge.", style = MaterialTheme.typography.bodySmall, color = Latch.colors.textMuted)
        }
        NameField(name) { name = it; result = null }
        ErrorNote(error)
        result?.let { f ->
            ResultPanel(listOf(f), f.name, "${formatBytes(f.length())} ready")
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------------------
// Compress PDF
// ---------------------------------------------------------------------------------------

@Composable
fun CompressPdfScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PickedFile?>(null) }
    var target by remember { mutableStateOf<Long?>(500 * 1024L) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<PdfTools.CompressResult?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            file = Files.describe(context, uri)
            result = null
            error = null
        }
    }

    ToolFrame(
        title = "Compress PDF",
        subtitle = "Shrink a PDF to the size you need",
        onBack = onBack,
        busyMessage = busy,
        progress = if (progress > 0f) progress else null,
        action = {
            PrimaryButton(
                "Compress",
                enabled = file != null && target != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val f = file ?: return@PrimaryButton
                    val t = target ?: return@PrimaryButton
                    error = null
                    result = null
                    busy = "Compressing…"
                    progress = 0f
                    scope.launch {
                        try {
                            val r = withContext(Dispatchers.IO) {
                                val local = Files.copyToFile(context, f.uri, File(Files.workDir(context), "in.pdf"))
                                val out = Files.exportFile(context, f.name.substringBeforeLast('.') + " (compressed)", "pdf")
                                PdfTools.compressToSize(local, t, out) { p, msg -> progress = p; busy = msg }
                            }
                            result = r
                        } catch (e: Throwable) {
                            error = e.message ?: "Compression failed"
                        } finally {
                            busy = null
                        }
                    }
                },
            )
        },
    ) {
        val f = file
        if (f == null) {
            AddFilesButton("Choose a PDF") { picker.launch(arrayOf(Mime.PDF)) }
        } else {
            FileRow(f, null, false) { file = null; result = null }
            Text("Change file", style = MaterialTheme.typography.labelLarge, color = Latch.colors.text, modifier = Modifier.pressable { picker.launch(arrayOf(Mime.PDF)) }.padding(4.dp))
        }
        SectionLabel("Target size")
        TargetSizePicker(listOf(100, 200, 300, 500, 1024, 2048), target) { target = it; result = null }
        if (target == null) Text("Enter a size of at least 10 KB", style = MaterialTheme.typography.bodySmall, color = Brand.Danger)
        Text(
            "To reach the size, pages are redrawn as images at the best quality that fits. Text in the result can't be selected or searched.",
            style = MaterialTheme.typography.bodySmall,
            color = Latch.colors.textMuted,
        )
        ErrorNote(error)
        val r = result
        val orig = f?.size ?: -1L
        if (r != null) {
            val saved = if (orig > 0) ((1 - r.size.toDouble() / orig) * 100).toInt() else 0
            val headline = when {
                r.alreadySmall -> "Already under the target"
                r.reachedTarget -> "Compressed to ${formatBytes(r.size)}"
                else -> "Smallest possible: ${formatBytes(r.size)}"
            }
            val detail = when {
                r.alreadySmall -> "The original is ${formatBytes(r.size)}, so it was kept unchanged."
                r.reachedTarget -> "From ${formatBytes(orig)}, $saved% smaller."
                else -> "This PDF can't get below the target without becoming unreadable. Try a larger size."
            }
            ResultPanel(listOf(r.file), headline, detail)
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------------------
// Compress image
// ---------------------------------------------------------------------------------------

private enum class Resize(val label: String, val maxDim: Int?) { KEEP("Keep size", null), P2048("2048 px", 2048), P1280("1280 px", 1280), P800("800 px", 800) }

@Composable
fun CompressImageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<PickedFile>() }
    var target by remember { mutableStateOf<Long?>(100 * 1024L) }
    var format by remember { mutableStateOf(ImageFormat.JPEG) }
    var resize by remember { mutableStateOf(Resize.KEEP) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ImageTools.Result>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        files.addAll(uris.map { Files.describe(context, it) }.filter { f -> files.none { it.uri == f.uri } })
        results = emptyList()
    }

    ToolFrame(
        title = "Compress image",
        subtitle = "Fit photos under an upload limit",
        onBack = onBack,
        busyMessage = busy,
        progress = if (progress > 0f) progress else null,
        action = {
            PrimaryButton(
                if (files.size > 1) "Compress ${files.size} images" else "Compress",
                enabled = files.isNotEmpty() && target != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val t = target ?: return@PrimaryButton
                    error = null
                    results = emptyList()
                    busy = "Compressing…"
                    progress = 0f
                    scope.launch {
                        try {
                            val list = withContext(Dispatchers.IO) {
                                files.toList().mapIndexed { i, f ->
                                    busy = "Compressing ${i + 1} of ${files.size}…"
                                    val out = Files.exportFile(context, f.name.substringBeforeLast('.') + " (compressed)", format.ext)
                                    val r = ImageTools.compressToSize(context, f.uri, t, format, resize.maxDim, out)
                                    progress = (i + 1f) / files.size
                                    r
                                }
                            }
                            results = list
                        } catch (e: Throwable) {
                            error = e.message ?: "Compression failed"
                        } finally {
                            busy = null
                        }
                    }
                },
            )
        },
    ) {
        files.forEach { f -> FileRow(f, null, false) { files.remove(f); results = emptyList() } }
        AddFilesButton(if (files.isEmpty()) "Choose images" else "Add more") {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        SectionLabel("Target size (each image)")
        TargetSizePicker(listOf(20, 50, 100, 200, 500, 1024), target) { target = it; results = emptyList() }
        if (target == null) Text("Enter a size of at least 10 KB", style = MaterialTheme.typography.bodySmall, color = Brand.Danger)
        SectionLabel("Format")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageFormat.entries.forEach { f -> Chip(f.label, format == f, { format = f; results = emptyList() }) }
        }
        SectionLabel("Longest side")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Resize.entries.forEach { r -> Chip(r.label, resize == r, { resize = r; results = emptyList() }) }
        }
        ErrorNote(error)
        if (results.isNotEmpty()) {
            val all = results.all { it.reachedTarget }
            val total = results.sumOf { it.size }
            ResultPanel(
                results.map { it.file },
                if (results.size == 1) "Compressed to ${formatBytes(results[0].size)}" else "${results.size} images, ${formatBytes(total)} total",
                if (results.size == 1) {
                    "${results[0].width} × ${results[0].height} px" + if (all) "" else ". Couldn't get under the target."
                } else if (all) {
                    "Every image is under the target."
                } else {
                    "${results.count { !it.reachedTarget }} couldn't get under the target."
                },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
