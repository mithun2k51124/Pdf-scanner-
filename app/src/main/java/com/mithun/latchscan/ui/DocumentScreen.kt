package com.mithun.latchscan.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mithun.latchscan.LatchApp
import com.mithun.latchscan.scanner.PageImage
import com.mithun.latchscan.scanner.pageKey
import com.mithun.latchscan.tools.ExportQuality
import com.mithun.latchscan.tools.Exporter
import com.mithun.latchscan.tools.PageSize
import com.mithun.latchscan.ui.theme.Brand
import com.mithun.latchscan.ui.theme.Latch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    docId: String,
    onBack: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onAddPages: () -> Unit,
) {
    val app = LatchApp.instance
    val context = LocalContext.current
    val docs by app.repository.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    val scope = rememberCoroutineScope()
    val saver = rememberFileSaver()
    val exporter = remember { Exporter(context.applicationContext, app.repository) }

    var pageSize by rememberSaveable { mutableStateOf(PageSize.A4) }
    var quality by rememberSaveable { mutableStateOf(ExportQuality.BALANCED) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var showOptions by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(doc == null) {
        if (doc == null && app.repository.loaded.value) onBack()
    }
    if (doc == null) return

    fun run(label: String, work: suspend () -> List<File>, then: (List<File>) -> Unit) {
        if (busy != null) return
        busy = label
        progress = 0f
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) { work() }
                then(files)
            } catch (t: Throwable) {
                Toast.makeText(context, t.message ?: "Something went wrong", Toast.LENGTH_LONG).show()
            } finally {
                busy = null
            }
        }
    }

    fun makePdf() = exporter.pdf(doc, pageSize, quality) { progress = it }

    Box(
        Modifier
            .fillMaxSize()
            .background(Latch.colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = doc.name,
                subtitle = "${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"}",
                onBack = onBack,
            ) {
                RoundIconButton(Icons.Rounded.Edit, "Rename", { renaming = true })
                RoundIconButton(Icons.Rounded.DeleteOutline, "Delete", { deleting = true }, tint = Brand.Danger)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(doc.pages, key = { _, p -> p.id }) { i, p ->
                    Column(Modifier.animateItem(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.72f)
                                .shadow(4.dp, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .pressable { onOpenPage(i) },
                        ) {
                            PageImage(File(app.repository.dirOf(doc.id), p.processed), pageKey(p), Modifier.fillMaxSize(), ContentScale.Fit)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Latch.colors.textMuted)
                    }
                }
            }

            // Export panel
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Latch.colors.surface)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .pressable { showOptions = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(Icons.Rounded.Tune, null, tint = Latch.colors.textMuted)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(
                        "PDF: ${pageSize.label}, ${quality.label.lowercase()} quality",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Latch.colors.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Change", style = MaterialTheme.typography.labelLarge, color = Latch.colors.text)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        "Share PDF",
                        { run("Making PDF…", { listOf(makePdf()) }) { com.mithun.latchscan.util.Files.share(context, it) } },
                        Modifier.weight(1f),
                        icon = Icons.Rounded.Share,
                    )
                    PrimaryButton(
                        "Save PDF",
                        { run("Making PDF…", { listOf(makePdf()) }) { saver.invoke(it[0]) } },
                        Modifier.weight(1f),
                        icon = Icons.Rounded.Download,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        "Word",
                        { run("Making Word file…", { listOf(exporter.docx(doc) { progress = it }) }) { com.mithun.latchscan.util.Files.share(context, it) } },
                        Modifier.weight(1f),
                        icon = Icons.Rounded.Article,
                    )
                    SecondaryButton(
                        "Images",
                        { run("Preparing images…", { exporter.images(doc) }) { com.mithun.latchscan.util.Files.share(context, it) } },
                        Modifier.weight(1f),
                        icon = Icons.Rounded.Image,
                    )
                    SecondaryButton("Add", onAddPages, Modifier.weight(1f), icon = Icons.Rounded.AddAPhoto)
                }
            }
        }
        BusyOverlay(busy != null, busy ?: "", if (progress > 0f) progress else null)
    }

    if (showOptions) {
        ModalBottomSheet(
            onDismissRequest = { showOptions = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Latch.colors.background,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
                Text("PDF settings", style = MaterialTheme.typography.titleLarge, color = Latch.colors.text)
                Spacer(Modifier.height(16.dp))
                Text("Page size", style = MaterialTheme.typography.titleSmall, color = Latch.colors.text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageSize.entries.forEach { s -> Chip(s.label, pageSize == s, { pageSize = s }) }
                }
                Spacer(Modifier.height(18.dp))
                Text("Quality", style = MaterialTheme.typography.titleSmall, color = Latch.colors.text)
                Text(
                    when (quality) {
                        ExportQuality.HIGH -> "Sharpest pages, bigger file"
                        ExportQuality.BALANCED -> "Clear text at a sensible size (recommended)"
                        ExportQuality.SMALL -> "Smallest file, fine for email and uploads"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Latch.colors.textMuted,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportQuality.entries.forEach { q -> Chip(q.label, quality == q, { quality = q }) }
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Done", { showOptions = false }, Modifier.fillMaxWidth())
            }
        }
    }
    if (renaming) {
        RenameDialog(doc.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { app.repository.rename(doc, name) }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${doc.name}\"?") },
            text = { Text("All ${doc.pages.size} page(s) will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    app.appScope.launch { app.deleteDocument(doc) }
                    onBack()
                }) { Text("Delete", color = Brand.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}
