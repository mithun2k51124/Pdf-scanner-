package com.mithun.latchscan.scanner

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mithun.latchscan.data.Pt
import com.mithun.latchscan.data.Quad
import com.mithun.latchscan.data.clamp01
import com.mithun.latchscan.ui.theme.Brand
import com.mithun.latchscan.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun lerp(a: Quad, b: Quad, t: Float): Quad {
    fun l(p: Pt, q: Pt) = Pt(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
    return Quad(l(a.tl, b.tl), l(a.tr, b.tr), l(a.br, b.br), l(a.bl, b.bl))
}

/**
 * Manual crop: drag the four corners (or the edges) of the page. A magnifier shows the
 * area under your finger so corners can be placed precisely.
 */
@Composable
fun CropEditor(
    original: File,
    initial: Quad,
    onConfirm: (Quad) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var quad by remember { mutableStateOf(initial) }
    var active by remember { mutableIntStateOf(-1) } // 0..3 corners, 4..7 edges
    var touch by remember { mutableStateOf(Offset.Zero) }

    BackHandler(onBack = onCancel)

    LaunchedEffect(original) {
        val b = withContext(Dispatchers.IO) { ImageProcessor.decodeSampled(original, 1800) }
        bitmap = b
        image = b?.asImageBitmap()
        if (b == null) {
            Toast.makeText(context, "Couldn't open this image", Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }

    fun animateTo(target: Quad) {
        val from = quad
        scope.launch {
            val a = Animatable(0f)
            a.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) { quad = lerp(from, target, value) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Ink),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DarkIcon(Icons.Rounded.Close, "Cancel", onCancel)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Adjust corners", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    if (quad.isValid()) "Drag corners or edges to fit the page" else "Corners cross over — drag them apart",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quad.isValid()) Color.White.copy(alpha = 0.65f) else Brand.Danger,
                )
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 28.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img == null) {
                CircularProgressIndicator(color = Brand.Highlighter)
            } else {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(img) {
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val r = fitRect(size.width.toFloat(), size.height.toFloat(), img)
                                    val handles = handlePoints(quad, r)
                                    val slop = 44.dp.toPx()
                                    val nearest = handles.indices.minByOrNull { (handles[it] - pos).getDistance() } ?: -1
                                    active = if (nearest >= 0 && (handles[nearest] - pos).getDistance() <= slop) nearest else -1
                                    touch = pos
                                },
                                onDrag = { change, drag ->
                                    if (active < 0) return@detectDragGestures
                                    change.consume()
                                    val r = fitRect(size.width.toFloat(), size.height.toFloat(), img)
                                    val dx = drag.x / r.width
                                    val dy = drag.y / r.height
                                    touch += drag
                                    quad = if (active < 4) {
                                        val p = quad.points[active]
                                        quad.with(active, Pt((p.x + dx).clamp01(), (p.y + dy).clamp01()))
                                    } else {
                                        val e = active - 4
                                        val a = e
                                        val b = (e + 1) % 4
                                        val pa = quad.points[a]
                                        val pb = quad.points[b]
                                        // Keep the edge inside the image while moving it.
                                        val minDx = -min(pa.x, pb.x)
                                        val maxDx = 1f - max(pa.x, pb.x)
                                        val minDy = -min(pa.y, pb.y)
                                        val maxDy = 1f - max(pa.y, pb.y)
                                        val cdx = dx.coerceIn(minDx, maxDx)
                                        val cdy = dy.coerceIn(minDy, maxDy)
                                        quad.with(a, Pt(pa.x + cdx, pa.y + cdy)).with(b, Pt(pb.x + cdx, pb.y + cdy))
                                    }
                                },
                                onDragEnd = { active = -1 },
                                onDragCancel = { active = -1 },
                            )
                        },
                ) {
                    val r = fitRect(size.width, size.height, img)
                    drawImage(
                        img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                    )
                    val pts = quad.points.map { Offset(r.left + it.x * r.width, r.top + it.y * r.height) }
                    val poly = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                        close()
                    }
                    val shade = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(r)
                        addPath(poly)
                    }
                    drawPath(shade, Color.Black.copy(alpha = 0.55f))
                    val valid = quad.isValid()
                    val lineColor = if (valid) Brand.Highlighter else Brand.Danger
                    drawPath(poly, lineColor, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))

                    // Edge handles
                    for (i in 0..3) {
                        val a = pts[i]
                        val b = pts[(i + 1) % 4]
                        val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                        drawCircle(Color.White, 7.dp.toPx(), mid)
                        drawCircle(lineColor, 7.dp.toPx(), mid, style = Stroke(2.dp.toPx()))
                    }
                    // Corner handles
                    pts.forEachIndexed { i, p ->
                        val selected = active == i
                        drawCircle(lineColor.copy(alpha = 0.25f), (if (selected) 26 else 18).dp.toPx(), p)
                        drawCircle(Color.White, 10.dp.toPx(), p)
                        drawCircle(lineColor, 10.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    }

                    // Magnifier
                    if (active in 0..3) {
                        val focus = pts[active]
                        val radius = 58.dp.toPx()
                        val zoom = 2.6f
                        val leftSide = focus.x < size.width / 2
                        val center = Offset(if (leftSide) size.width - radius - 4f else radius + 4f, radius + 4f)
                        val scale = img.width / r.width
                        val srcHalf = radius / zoom * scale
                        val sx = ((focus.x - r.left) * scale - srcHalf).roundToInt()
                        val sy = ((focus.y - r.top) * scale - srcHalf).roundToInt()
                        val circle = Path().apply { addOval(Rect(center, radius)) }
                        clipPath(circle) {
                            drawRect(Color.Black, topLeft = center - Offset(radius, radius), size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2))
                            val srcSize = (srcHalf * 2).roundToInt().coerceAtLeast(1)
                            // Clamp the sampled square to the bitmap, shifting the destination to match.
                            val cx0 = sx.coerceIn(0, max(0, img.width - 1))
                            val cy0 = sy.coerceIn(0, max(0, img.height - 1))
                            val cx1 = (sx + srcSize).coerceIn(0, img.width)
                            val cy1 = (sy + srcSize).coerceIn(0, img.height)
                            if (cx1 > cx0 && cy1 > cy0) {
                                val k = (radius * 2) / srcSize
                                drawImage(
                                    img,
                                    srcOffset = IntOffset(cx0, cy0),
                                    srcSize = IntSize(cx1 - cx0, cy1 - cy0),
                                    dstOffset = IntOffset(
                                        (center.x - radius + (cx0 - sx) * k).roundToInt(),
                                        (center.y - radius + (cy0 - sy) * k).roundToInt(),
                                    ),
                                    dstSize = IntSize(((cx1 - cx0) * k).roundToInt(), ((cy1 - cy0) * k).roundToInt()),
                                )
                            }
                            // Crosshair
                            val c = 9.dp.toPx()
                            drawLine(lineColor, center - Offset(c, 0f), center + Offset(c, 0f), 2.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(lineColor, center - Offset(0f, c), center + Offset(0f, c), 2.dp.toPx(), cap = StrokeCap.Round)
                        }
                        drawCircle(Color.White, radius, center, style = Stroke(3.dp.toPx()))
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolPill(Icons.Rounded.AutoFixHigh, "Auto", Modifier.weight(1f)) {
                val b = bitmap ?: return@ToolPill
                scope.launch {
                    val found = withContext(Dispatchers.Default) { EdgeDetector.detect(b) }
                    if (found != null) animateTo(found) else Toast.makeText(context, "No page edges found — drag the corners", Toast.LENGTH_SHORT).show()
                }
            }
            ToolPill(Icons.Rounded.CropFree, "Full image", Modifier.weight(1f)) { animateTo(Quad.FULL) }
            Box(
                Modifier
                    .weight(1.2f)
                    .height(52.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                    .background(if (quad.isValid()) Brand.Highlighter else Color.White.copy(alpha = 0.15f))
                    .pressable(enabled = quad.isValid() && image != null) { onConfirm(quad) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Check, null, tint = Brand.Ink)
                    Spacer(Modifier.width(6.dp))
                    Text("Done", style = MaterialTheme.typography.labelLarge, color = Brand.Ink)
                }
            }
        }
    }
}

private fun fitRect(w: Float, h: Float, img: ImageBitmap): Rect {
    val s = min(w / img.width, h / img.height)
    val dw = img.width * s
    val dh = img.height * s
    val left = (w - dw) / 2f
    val top = (h - dh) / 2f
    return Rect(left, top, left + dw, top + dh)
}

/** Corners (0..3) followed by edge midpoints (4..7: top, right, bottom, left). */
private fun handlePoints(q: Quad, r: Rect): List<Offset> {
    val pts = q.points.map { Offset(r.left + it.x * r.width, r.top + it.y * r.height) }
    val mids = List(4) { i -> val a = pts[i]; val b = pts[(i + 1) % 4]; Offset((a.x + b.x) / 2, (a.y + b.y) / 2) }
    return pts + mids
}

@Composable
private fun DarkIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .pressable(onClick = onClick)
            .padding(10.dp),
    ) { Icon(icon, label, tint = Color.White) }
}

@Composable
private fun ToolPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(52.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .pressable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
