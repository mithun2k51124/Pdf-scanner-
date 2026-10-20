package com.mithun.latchscan.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A vertical list whose rows can be long-pressed and dragged to a new position.
 * [onMove] is called with (from, to) every time the dragged row passes a neighbour.
 */
@Composable
fun <T> ReorderableList(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (index: Int, item: T, dragging: Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentOnMove by rememberUpdatedState(onMove)

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { pos.y.toInt() in it.offset..(it.offset + it.size) }
                    if (hit != null) {
                        draggingKey = hit.key
                        dragOffset = 0f
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, amount ->
                    val dk = draggingKey ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    dragOffset += amount.y
                    val info = listState.layoutInfo.visibleItemsInfo
                    val cur = info.firstOrNull { it.key == dk } ?: return@detectDragGesturesAfterLongPress
                    val middle = (cur.offset + dragOffset + cur.size / 2f).toInt()
                    val target = info.firstOrNull { it.key != dk && middle in it.offset..(it.offset + it.size) }
                    if (target != null) {
                        val movingDown = target.index > cur.index
                        // Only swap once the dragged row's centre passes the neighbour's centre.
                        val targetMid = target.offset + target.size / 2
                        if ((movingDown && middle >= targetMid) || (!movingDown && middle <= targetMid)) {
                            currentOnMove(cur.index, target.index)
                            dragOffset += (cur.offset - target.offset).toFloat()
                        }
                    }
                },
                onDragEnd = { draggingKey = null; dragOffset = 0f },
                onDragCancel = { draggingKey = null; dragOffset = 0f },
            )
        },
    ) {
        itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            val dragging = key(item) == draggingKey
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .then(if (dragging) Modifier else Modifier.animateItem())
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else 0f
                        val s = if (dragging) 1.03f else 1f
                        scaleX = s
                        scaleY = s
                        shadowElevation = if (dragging) 18f else 0f
                    },
            ) {
                itemContent(index, item, dragging)
            }
        }
    }
}
