package com.mithun.latchscan.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mithun.latchscan.LatchApp
import com.mithun.latchscan.data.ScanDocument
import com.mithun.latchscan.scanner.PageImage
import com.mithun.latchscan.scanner.pageKey
import com.mithun.latchscan.ui.theme.Brand
import com.mithun.latchscan.ui.theme.Latch
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HomeTab(val label: String) { SCANS("Scans"), TOOLS("Tools") }

enum class Tool(val title: String, val blurb: String, val icon: ImageVector, val route: String) {
    MERGE_PDF("Merge PDFs", "Join several PDFs into one, in any order", Icons.Rounded.Layers, "tool/merge_pdf"),
    MERGE_DOCS("Merge any documents", "PDF, Word, images and text into one PDF or Word file", Icons.Rounded.Description, "tool/merge_docs"),
    COMPRESS_PDF("Compress PDF", "Shrink a PDF to a size you choose, like 200 KB", Icons.Rounded.PictureAsPdf, "tool/compress_pdf"),
    COMPRESS_IMAGE("Compress image", "Make photos fit an upload limit, like 100 KB", Icons.Rounded.Image, "tool/compress_image"),
}

@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onImported: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onTool: (String) -> Unit,
) {
    val app = LatchApp.instance
    val context = androidx.compose.ui.platform.LocalContext.current
    val docs by app.repository.docs.collectAsState()
    val loaded by app.repository.loaded.collectAsState()
    var tab by rememberSaveable { mutableStateOf(HomeTab.SCANS) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            app.session.start()
            scope.launch {
                val n = app.session.importImages(uris)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    importing = false
                    if (n > 0) onImported() else Toast.makeText(context, "Couldn't open those images", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Latch.colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp),
            ) {
                Wordmark()
                Spacer(Modifier.height(18.dp))
                TabSwitch(tab) { tab = it }
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(320)) { it / 5 * dir } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(320)) { -it / 5 * dir } + fadeOut(tween(160)))
                },
                modifier = Modifier.weight(1f),
                label = "tab",
            ) { t ->
                when (t) {
                    HomeTab.SCANS -> ScansList(docs, loaded, onOpenDoc)
                    HomeTab.TOOLS -> ToolsList(onTool) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
            }
        }

        // Fade the list out under the floating scan bar.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(96.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Latch.colors.background))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(Latch.colors.background),
        )
        // Scan bar
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                "Scan a document",
                onScan,
                Modifier
                    .weight(1f)
                    .shadowSoft(),
                icon = Icons.Rounded.CameraAlt,
                height = 60.dp,
            )
            RoundIconButton(
                Icons.Rounded.PhotoLibrary,
                "Import photos",
                { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                size = 60.dp,
                background = Latch.colors.surface,
                modifier = Modifier.shadowSoft(),
            )
        }
        BusyOverlay(importing, "Finding page edges…")
    }
}

private fun Modifier.shadowSoft(): Modifier =
    this.shadow(elevation = 10.dp, shape = RoundedCornerShape(30.dp), ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))

/** "Latch" set large and tight, with the highlighter brackets from the icon around the dot. */
@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Latch Scan", style = MaterialTheme.typography.displaySmall, color = Latch.colors.text)
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.size(22.dp)) {
            val l = size.width * 0.38f
            val s = 3.dp.toPx()
            val c = Brand.HighlighterDeep
            drawLine(c, Offset(0f, l), Offset(0f, 0f), s, StrokeCap.Round)
            drawLine(c, Offset(0f, 0f), Offset(l, 0f), s, StrokeCap.Round)
            drawLine(c, Offset(size.width, size.height - l), Offset(size.width, size.height), s, StrokeCap.Round)
            drawLine(c, Offset(size.width, size.height), Offset(size.width - l, size.height), s, StrokeCap.Round)
        }
    }
}

@Composable
private fun TabSwitch(tab: HomeTab, onChange: (HomeTab) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Latch.colors.surfaceAlt)
            .padding(4.dp),
    ) {
        val segW = maxWidth / HomeTab.entries.size
        val x by animateDpAsState(segW * tab.ordinal, spring(dampingRatio = 0.75f, stiffness = 500f), label = "tabX")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Latch.colors.surface),
        )
        Row(Modifier.fillMaxWidth()) {
            HomeTab.entries.forEach { t ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .pressable(role = androidx.compose.ui.semantics.Role.Tab) { onChange(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (t == tab) Latch.colors.text else Latch.colors.textMuted,
                    )
                }
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

@Composable
private fun ScansList(docs: List<ScanDocument>, loaded: Boolean, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isBlank()) docs else docs.filter { it.name.contains(query.trim(), ignoreCase = true) }
    if (loaded && docs.isEmpty()) {
        EmptyScans()
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (docs.size >= 4) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search scans") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Latch.colors.surface,
                        focusedContainerColor = Latch.colors.surface,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Brand.HighlighterDeep,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(shown, key = { it.id }) { doc ->
            DocRow(doc, Modifier.animateItem()) { onOpen(doc.id) }
        }
        if (shown.isEmpty() && docs.isNotEmpty()) {
            item(key = "none") {
                Text("No scans match \"$query\"", color = Latch.colors.textMuted, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocRow(doc: ScanDocument, modifier: Modifier, onClick: () -> Unit) {
    val app = LatchApp.instance
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val first = doc.pages.firstOrNull()
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Latch.colors.surface)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Stacked-sheets thumbnail: a hint of how many pages are inside.
            Box(Modifier.size(width = 60.dp, height = 74.dp), contentAlignment = Alignment.Center) {
                if (doc.pages.size > 1) {
                    Box(
                        Modifier
                            .size(width = 50.dp, height = 66.dp)
                            .rotate(6f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Latch.colors.surfaceAlt),
                    )
                }
                Box(
                    Modifier
                        .size(width = 50.dp, height = 66.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White),
                ) {
                    if (first != null) {
                        PageImage(File(app.repository.dirOf(doc.id), first.processed), pageKey(first), Modifier.fillMaxSize(), ContentScale.Crop)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, color = Latch.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"}, ${dateFmt.format(Date(doc.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Latch.colors.textMuted,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onClick() })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
            DropdownMenuItem(text = { Text("Delete", color = Brand.Danger) }, onClick = { menu = false; deleting = true })
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
                    scope.launch { app.deleteDocument(doc) }
                }) { Text("Delete", color = Brand.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.HighlighterDeep),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(text) }, enabled = text.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyScans() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(bottom = 100.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(width = 150.dp, height = 170.dp)) {
            val w = size.width
            val h = size.height
            // Sheet of paper, slightly turned
            val sheet = Path().apply {
                moveTo(w * 0.22f, h * 0.12f)
                lineTo(w * 0.84f, h * 0.18f)
                lineTo(w * 0.78f, h * 0.90f)
                lineTo(w * 0.16f, h * 0.84f)
                close()
            }
            drawPath(sheet, Color.White)
            drawPath(sheet, Color(0x22000000), style = Stroke(1.dp.toPx()))
            listOf(0.32f, 0.42f, 0.52f, 0.62f).forEachIndexed { i, y ->
                val end = if (i == 3) 0.55f else 0.72f
                drawLine(Color(0xFFC9D0DA), Offset(w * 0.3f, h * y), Offset(w * end, h * (y + 0.02f)), 4.dp.toPx(), StrokeCap.Round)
            }
            val c = Brand.HighlighterDeep
            val s = 5.dp.toPx()
            val l = 22.dp.toPx()
            fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(c, Offset(x, y + dy * l), Offset(x, y), s, StrokeCap.Round)
                drawLine(c, Offset(x, y), Offset(x + dx * l, y), s, StrokeCap.Round)
            }
            bracket(w * 0.12f, h * 0.04f, 1f, 1f)
            bracket(w * 0.94f, h * 0.10f, -1f, 1f)
            bracket(w * 0.88f, h * 0.98f, -1f, -1f)
            bracket(w * 0.06f, h * 0.92f, 1f, -1f)
        }
        Spacer(Modifier.height(24.dp))
        Text("No scans yet", style = MaterialTheme.typography.headlineSmall, color = Latch.colors.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Put a page on a table and tap Scan. Latch finds the edges and takes the photo by itself.",
            style = MaterialTheme.typography.bodyLarge,
            color = Latch.colors.textMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ToolsList(onTool: (String) -> Unit, onImport: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(Tool.entries, key = { it.name }) { t ->
            ToolRow(t.icon, t.title, t.blurb) { onTool(t.route) }
        }
        item(key = "import") {
            ToolRow(Icons.Rounded.PhotoLibrary, "Photos to PDF", "Turn gallery photos into a cropped, clean scan", onImport)
        }
        item(key = "note") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 4.dp, end = 4.dp),
            ) {
                Icon(Icons.Rounded.Lock, null, tint = Latch.colors.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Everything runs on your phone. Files are never uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Latch.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, blurb: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Latch.colors.surface)
            .pressable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Brand.Highlighter),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Brand.Ink) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Latch.colors.text)
            Spacer(Modifier.height(2.dp))
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = Latch.colors.textMuted)
        }
    }
}
