package com.mithun.latchscan.scanner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.mithun.latchscan.data.Filter
import com.mithun.latchscan.data.Page
import com.mithun.latchscan.data.Side
import com.mithun.latchscan.ui.Chip
import com.mithun.latchscan.ui.ReorderableList
import com.mithun.latchscan.ui.ScreenHeader
import com.mithun.latchscan.ui.pressable
import com.mithun.latchscan.ui.theme.Brand
import com.mithun.latchscan.ui.theme.Latch
import kotlinx.coroutines.launch
import java.io.File

/** Thumbnail / preview of a processed page. [rev] busts the image cache after edits. */
@Composable
fun PageImage(file: File, cacheKey: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(file)
            .memoryCacheKey(cacheKey)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(180)
            .build(),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}

fun pageKey(p: Page) = p.id + "_" + p.rev

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PagesEditor(
    host: PageHost,
    title: String,
    onBack: () -> Unit,
    onCrop: (Page) -> Unit,
    headerActions: @Composable RowScope.() -> Unit = {},
    extraContent: @Composable ColumnScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit,
    initialPage: Int = 0,
) {
    val pages = host.pages
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) { pages.size }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val spin = remember { mutableStateMapOf<String, Float>() }
    var showArrange by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Page?>(null) }
    val current = pages.getOrNull(pagerState.currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))

    fun edit(page: Page, block: (Page) -> Page) {
        if (busy[page.id] == true) return
        busy[page.id] = true
        scope.launch {
            try {
                host.update(block(page))
            } finally {
                busy.remove(page.id)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Latch.colors.background),
    ) {
        ScreenHeader(
            title = title,
            subtitle = if (pages.isEmpty()) "No pages" else "Page ${pagerState.currentPage + 1} of ${pages.size}",
            onBack = onBack,
            actions = headerActions,
        )

        if (pages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("All pages were removed.\nGo back to scan more.", color = Latch.colors.textMuted, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 36.dp),
                pageSpacing = 16.dp,
                key = { pages.getOrNull(it)?.id ?: it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { index ->
                val page = pages.getOrNull(index) ?: return@HorizontalPager
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val rot = spin[page.id] ?: 0f
                    PageImage(
                        file = host.file(page.processed),
                        cacheKey = pageKey(page),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = rot },
                    )
                    if (page.side != Side.NONE) {
                        Text(
                            if (page.side == Side.FRONT) "Front" else "Back",
                            style = MaterialTheme.typography.labelMedium,
                            color = Brand.Ink,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brand.Highlighter)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if (busy[page.id] == true) {
                        CircularProgressIndicator(color = Brand.HighlighterDeep)
                    }
                }
            }

            ThumbStrip(host, pages, pagerState.currentPage) { scope.launch { pagerState.animateScrollToPage(it) } }

            Spacer(Modifier.height(10.dp))
            if (current != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Filter.entries.forEach { f ->
                        Chip(f.label, current.filter == f, { if (current.filter != f) edit(current) { it.copy(filter = f) } }, Modifier.weight(1f))
                    }
                }
                if (pages.size > 1) {
                    Text(
                        "Use ${current.filter.label} on every page",
                        style = MaterialTheme.typography.labelMedium,
                        color = Latch.colors.textMuted,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .pressable {
                                val f = current.filter
                                pages.toList().filter { it.filter != f }.forEach { p -> edit(p) { it.copy(filter = f) } }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ActionItem(Icons.Rounded.Crop, "Crop") { onCrop(current) }
                    ActionItem(Icons.Rounded.RotateRight, "Rotate") {
                        if (busy[current.id] != true) {
                            val anim = Animatable(0f)
                            scope.launch {
                                anim.animateTo(90f, tween(260, easing = FastOutSlowInEasing)) { spin[current.id] = value }
                            }
                            scope.launch {
                                busy[current.id] = true
                                try {
                                    host.update(current.copy(rotation = (current.rotation + 90) % 360))
                                } finally {
                                    busy.remove(current.id)
                                    spin.remove(current.id)
                                }
                            }
                        }
                    }
                    ActionItem(Icons.Rounded.SwapVert, "Arrange", enabled = pages.size > 1) { showArrange = true }
                    ActionItem(Icons.Rounded.DeleteOutline, "Delete", danger = true) { confirmDelete = current }
                }
            }
        }
        extraContent()
        Box(Modifier.navigationBarsPadding()) { bottomBar() }
    }

    confirmDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this page?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { host.delete(page) }
                }) { Text("Delete", color = Brand.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (showArrange) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showArrange = false }, sheetState = sheet, containerColor = Latch.colors.background) {
            Text(
                "Arrange pages",
                style = MaterialTheme.typography.titleLarge,
                color = Latch.colors.text,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "Long-press a page, then drag it",
                style = MaterialTheme.typography.bodySmall,
                color = Latch.colors.textMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            ReorderableList(
                items = pages.toList(),
                key = { it.id },
                onMove = { from, to -> scope.launch { host.reorder(from, to) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { index, page, dragging ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (dragging) Brand.Highlighter.copy(alpha = 0.25f) else Latch.colors.surface)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageImage(
                        host.file(page.processed),
                        pageKey(page),
                        Modifier
                            .size(width = 48.dp, height = 64.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White),
                        ContentScale.Crop,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("Page ${index + 1}", style = MaterialTheme.typography.titleMedium, color = Latch.colors.text, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.DragHandle, "Drag to reorder", tint = Latch.colors.textMuted)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

@Composable
private fun ThumbStrip(host: PageHost, pages: List<Page>, selected: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) {
        if (pages.isNotEmpty()) state.animateScrollToItem((selected - 2).coerceAtLeast(0))
    }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
            val isSel = i == selected
            Box(
                Modifier
                    .animateItem()
                    .size(width = 40.dp, height = 54.dp)
                    .shadow(if (isSel) 6.dp else 0.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White)
                    .border(if (isSel) 2.5.dp else 1.dp, if (isSel) Brand.HighlighterDeep else Latch.colors.outline, RoundedCornerShape(6.dp))
                    .pressable { onSelect(i) },
            ) {
                PageImage(host.file(p.processed), pageKey(p), Modifier.fillMaxSize(), ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> Latch.colors.textMuted.copy(alpha = 0.5f)
        danger -> Brand.Danger
        else -> Latch.colors.text
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}
