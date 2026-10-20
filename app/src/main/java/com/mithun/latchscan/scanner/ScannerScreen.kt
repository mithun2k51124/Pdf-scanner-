package com.mithun.latchscan.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mithun.latchscan.LatchApp
import com.mithun.latchscan.data.Filter
import com.mithun.latchscan.data.Pt
import com.mithun.latchscan.data.Quad
import com.mithun.latchscan.data.Side
import com.mithun.latchscan.ui.Chip
import com.mithun.latchscan.ui.DarkScreenSystemBars
import com.mithun.latchscan.ui.PrimaryButton
import com.mithun.latchscan.ui.pressable
import com.mithun.latchscan.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val HOLD_MS = 1100L
private const val STABLE_TOLERANCE = 0.022f

@Composable
fun ScannerScreen(
    onClose: () -> Unit,
    onReview: () -> Unit,
    onCrop: (pageId: String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val session = LatchApp.instance.session
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importing = true
            scope.launch {
                val n = session.importImages(uris)
                withContext(Dispatchers.Main) {
                    importing = false
                    if (n == 0) Toast.makeText(context, "Couldn't open those images", Toast.LENGTH_SHORT).show() else onReview()
                }
            }
        }
    }
    val openGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    var confirmDiscard by remember { mutableStateOf(false) }
    val tryClose = {
        if (session.pages.isNotEmpty()) confirmDiscard = true else { session.clear(); onClose() }
    }
    BackHandler { tryClose() }
    DarkScreenSystemBars(keepScreenOn = true)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brand.Ink),
    ) {
        if (hasPermission) {
            CameraContent(
                onClose = tryClose,
                onReview = onReview,
                onCrop = onCrop,
                openGallery = openGallery,
            )
        } else {
            PermissionPane(
                denied = askedOnce,
                onAsk = {
                    if (askedOnce) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        context.startActivity(intent)
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onGallery = openGallery,
                onClose = tryClose,
            )
        }
        if (importing) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Brand.Highlighter)
                    Spacer(Modifier.height(12.dp))
                    Text("Finding page edges…", color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }

    // Re-check after returning from system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this scan?") },
            text = { Text("${session.pages.size} captured page(s) will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    session.clear()
                    onClose()
                }) { Text("Discard", color = Brand.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep scanning") } },
        )
    }
}

@Composable
private fun PermissionPane(denied: Boolean, onAsk: () -> Unit, onGallery: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
                .pressable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.CameraAlt, null, tint = Brand.Highlighter, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Camera access is needed to scan", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            if (denied) "Camera permission is turned off. Turn it on in Settings, or import photos from your gallery instead."
            else "Latch Scan uses the camera only while this screen is open. Photos never leave your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(if (denied) "Open settings" else "Allow camera", onAsk, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(26.dp))
                .pressable(onClick = onGallery),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PhotoLibrary, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Import from gallery", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.weight(1f))
    }
}

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
        try {
            cont.resume(future.get())
        } catch (t: Throwable) {
            cont.resumeWithException(t)
        }
    }, ContextCompat.getMainExecutor(this))
}

private suspend fun ImageCapture.capture(context: Context): ImageProxy = suspendCancellableCoroutine { cont ->
    takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            if (cont.isActive) cont.resume(image) else image.close()
        }

        override fun onError(exception: ImageCaptureException) {
            if (cont.isActive) cont.resumeWithException(exception)
        }
    })
}

/** Converts a captured frame to an upright, size-capped bitmap. */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    try {
        val raw = toBitmap()
        val rotated = ImageProcessor.rotate(raw, imageInfo.rotationDegrees)
        return ImageProcessor.scaleDown(rotated, ImageProcessor.MAX_DIM)
    } finally {
        close()
    }
}

@Composable
private fun CameraContent(
    onClose: () -> Unit,
    onReview: () -> Unit,
    onCrop: (String) -> Unit,
    openGallery: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val session = LatchApp.instance.session

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analyzer = remember { FrameAnalyzer { true } }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val provider = context.cameraProvider()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build(),
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, analyzer)
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
            imageCapture = capture
        } catch (t: Throwable) {
            cameraError = "Camera unavailable: ${t.message ?: "unknown error"}"
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysisExecutor.shutdown()
            analyzer.release()
            sound.release()
        }
    }

    val detection by analyzer.detections.collectAsState()
    var capturing by remember { mutableStateOf(false) }
    val tracker = remember { LatchTracker(holdMs = HOLD_MS, tolerance = STABLE_TOLERANCE) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    val flash = remember { Animatable(0f) }
    var toast by remember { mutableStateOf<String?>(null) }
    var flyingImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val fly = remember { Animatable(0f) }

    val auto = session.autoCapture

    suspend fun doCapture(fromAuto: Boolean) {
        val ic = imageCapture ?: return
        if (capturing) return
        capturing = true
        val previewQuad = detection?.quad
        try {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) }
            scope.launch {
                flash.snapTo(0.85f)
                flash.animateTo(0f, tween(380))
            }
            val proxy = ic.capture(context)
            val bmp = withContext(Dispatchers.Default) { proxy.toUprightBitmap() }
            val found = withContext(Dispatchers.Default) { EdgeDetector.detect(bmp) }
            val quad = found ?: previewQuad
            tracker.onCaptured(previewQuad ?: quad)
            holdProgress = 0f
            if (!session.autoCapture && !fromAuto) {
                // No outline found: keep the photo's real colours until the user crops it.
                val page = session.addCapture(bmp, quad ?: Quad.inset(0.04f), if (quad != null) Filter.ENHANCED else Filter.ORIGINAL)
                bmp.recycle()
                withContext(Dispatchers.Main) { onCrop(page.id) }
            } else {
                val thumb = withContext(Dispatchers.Default) {
                    Bitmap.createScaledBitmap(bmp, 360, (360f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
                }
                flyingImage = thumb.asImageBitmap()
                scope.launch {
                    fly.snapTo(0f)
                    fly.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
                    flyingImage = null
                }
                val page = session.addCapture(bmp, quad)
                bmp.recycle()
                toast = when (page.side) {
                    Side.FRONT -> "Front captured. Now flip it over"
                    Side.BACK -> "Back captured"
                    Side.NONE -> "Page ${session.pages.size} captured. Place the next page"
                }
            }
        } catch (t: Throwable) {
            toast = "Couldn't take the photo. Try again."
            tracker.onCaptureFailed()
        } finally {
            capturing = false
        }
    }

    // Auto-capture: when the outline holds still long enough, take the shot.
    val autoState by rememberUpdatedState(auto)
    LaunchedEffect(detection) {
        val d = detection ?: return@LaunchedEffect
        val state = tracker.update(d.quad, d.timeMs, autoState)
        holdProgress = state.progress
        if (state.capture && !capturing && imageCapture != null) {
            scope.launch { doCapture(fromAuto = true) }
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2200)
            toast = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopControls(
            auto = auto,
            onAutoChange = {
                session.autoCapture = it
                holdProgress = 0f
                tracker.resetHold()
            },
            torchOn = torchOn,
            torchAvailable = camera?.cameraInfo?.hasFlashUnit() == true,
            onTorch = {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            },
            onClose = onClose,
        )

        // Viewfinder: exactly 3:4 so the preview, analysis frames and photos line up 1:1.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val fitW = if (maxWidth / maxHeight > 3f / 4f) maxHeight * 0.75f else maxWidth
            Box(
                Modifier
                    .width(fitW)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                val cam = camera ?: return@detectTapGestures
                                val point = previewView.meteringPointFactory.createPoint(pos.x, pos.y)
                                cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                            }
                        },
                )
                QuadOverlay(
                    quad = detection?.quad,
                    progress = if (auto) holdProgress else 0f,
                    modifier = Modifier.fillMaxSize(),
                )
                FlyingPage(flyingImage, fly.value)
                // Shutter flash
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = flash.value }
                        .background(Color.White),
                )
                HintPill(
                    text = toast ?: hintText(detection?.quad != null, auto, holdProgress, session.mode, session.nextSide(), cameraError),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp),
                )
                if (session.mode == CaptureMode.FRONT_BACK) {
                    SideBadge(
                        side = session.nextSide(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp),
                    )
                }
            }
        }

        BottomControls(
            auto = auto,
            holdProgress = holdProgress,
            capturing = capturing,
            onShutter = { scope.launch { doCapture(fromAuto = false) } },
            onGallery = openGallery,
            onReview = onReview,
        )
    }
}

private fun hintText(found: Boolean, auto: Boolean, progress: Float, mode: CaptureMode, side: Side, error: String?): String = when {
    error != null -> error
    !found && mode == CaptureMode.FRONT_BACK && side == Side.BACK -> "Flip the page over and place the back side"
    !found -> "Looking for a page…"
    !auto -> "Page found — tap the shutter"
    progress > 0.15f -> "Hold steady…"
    else -> "Page found"
}

@Composable
private fun HintPill(text: String, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(180)) + slideInVertically { -it / 3 }) togetherWith (fadeOut(tween(120)) + slideOutVertically { it / 3 })
        },
        modifier = modifier,
        label = "hint",
    ) { t ->
        Text(
            t,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun SideBadge(side: Side, modifier: Modifier = Modifier) {
    val flip by animateFloatAsState(if (side == Side.BACK) 180f else 0f, tween(600, easing = FastOutSlowInEasing), label = "flip")
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 16.dp, height = 21.dp)
                .graphicsLayer {
                    rotationY = flip
                    cameraDistance = 12f * density
                }
                .clip(RoundedCornerShape(3.dp))
                .background(if (flip > 90f) Brand.Highlighter else Color.White),
        )
        Spacer(Modifier.width(10.dp))
        Text(if (side == Side.BACK) "Back side" else "Front side", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** Highlighter-yellow outline that springs onto the detected page and fills as it locks. */
@Composable
private fun QuadOverlay(quad: Quad?, progress: Float, modifier: Modifier = Modifier) {
    val corners = remember { List(8) { Animatable(0.5f) } }
    var hasShown by remember { mutableStateOf(false) }
    val visible by animateFloatAsState(if (quad != null) 1f else 0f, tween(if (quad != null) 160 else 420), label = "quadAlpha")
    LaunchedEffect(quad) {
        val q = quad ?: return@LaunchedEffect
        val target = q.points.flatMap { listOf(it.x, it.y) }
        if (!hasShown) {
            target.forEachIndexed { i, v -> corners[i].snapTo(v) }
            hasShown = true
        } else {
            target.forEachIndexed { i, v ->
                launch { corners[i].animateTo(v, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
            }
        }
    }
    LaunchedEffect(visible) { if (visible == 0f) hasShown = false }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    val locked = progress >= 0.999f

    Canvas(modifier) {
        if (visible <= 0.01f) return@Canvas
        val pts = List(4) { Offset(corners[it * 2].value * size.width, corners[it * 2 + 1].value * size.height) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1..3) lineTo(pts[i].x, pts[i].y)
            close()
        }
        val fillAlpha = (0.10f + 0.28f * progress) * visible
        drawPath(path, Brand.Highlighter.copy(alpha = fillAlpha))
        drawPath(
            path,
            Brand.Highlighter.copy(alpha = (if (locked) 1f else glow.coerceAtLeast(progress)) * visible),
            style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round),
        )
        // Corner brackets
        val len = 22.dp.toPx()
        val stroke = 5.dp.toPx()
        for (i in 0..3) {
            val c = pts[i]
            val prev = pts[(i + 3) % 4]
            val next = pts[(i + 1) % 4]
            fun toward(target: Offset): Offset {
                val d = target - c
                val l = d.getDistance().coerceAtLeast(1f)
                return c + d * (len.coerceAtMost(l / 3f) / l)
            }
            val a = toward(prev)
            val b = toward(next)
            val bracket = Path().apply { moveTo(a.x, a.y); lineTo(c.x, c.y); lineTo(b.x, b.y) }
            drawPath(bracket, Brand.Highlighter.copy(alpha = visible), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The photo just taken shrinks toward the page stack (bottom right). */
@Composable
private fun FlyingPage(image: ImageBitmap?, t: Float) {
    if (image == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val endW = 46.dp
        val endH = 60.dp
        val endX = w - endW - 8.dp
        val endY = h - endH - 8.dp
        val x = endX * t
        val y = endY * t
        val ww = w + (endW - w) * t
        val hh = h + (endH - h) * t
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset(x, y)
                .size(ww, hh)
                .graphicsLayer {
                    alpha = if (t < 0.8f) 1f else (1f - (t - 0.8f) / 0.2f)
                    rotationZ = -5f * t
                }
                .clip(RoundedCornerShape(6.dp + 6.dp * t))
                .border(2.dp, Brand.Highlighter.copy(alpha = t), RoundedCornerShape(6.dp + 6.dp * t)),
        )
    }
}

@Composable
private fun TopControls(
    auto: Boolean,
    onAutoChange: (Boolean) -> Unit,
    torchOn: Boolean,
    torchAvailable: Boolean,
    onTorch: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleControl(Icons.Rounded.Close, "Close", onClose)
        Spacer(Modifier.weight(1f))
        AutoManualToggle(auto, onAutoChange)
        Spacer(Modifier.weight(1f))
        CircleControl(
            if (torchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
            if (torchOn) "Turn torch off" else "Turn torch on",
            onTorch,
            enabled = torchAvailable,
            active = torchOn,
        )
    }
}

@Composable
private fun CircleControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, active: Boolean = false) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) Brand.Highlighter else Color.White.copy(alpha = 0.10f))
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) Brand.Ink else Color.White.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

/** Sliding two-position switch: Auto (latches and shoots by itself) / Manual. */
@Composable
private fun AutoManualToggle(auto: Boolean, onChange: (Boolean) -> Unit) {
    val segW = 84.dp
    val offset by animateDpAsState(if (auto) 0.dp else segW, spring(dampingRatio = 0.7f, stiffness = 500f), label = "toggle")
    Box(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(4.dp)
            .semantics { contentDescription = if (auto) "Auto capture on" else "Manual capture" },
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(width = segW, height = 36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brand.Highlighter),
        )
        Row {
            listOf(true to "Auto", false to "Manual").forEach { (value, label) ->
                Box(
                    Modifier
                        .size(width = segW, height = 36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .pressable(role = Role.Switch) { onChange(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (auto == value) Brand.Ink else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomControls(
    auto: Boolean,
    holdProgress: Float,
    capturing: Boolean,
    onShutter: () -> Unit,
    onGallery: () -> Unit,
    onReview: () -> Unit,
) {
    val session = LatchApp.instance.session
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureMode.entries.forEach { m ->
                Chip(
                    m.label,
                    selected = session.mode == m,
                    onClick = { if (session.mode != m) session.mode = m },
                    dark = true,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                CircleControl(Icons.Rounded.PhotoLibrary, "Import from gallery", onGallery)
            }
            ShutterButton(auto, holdProgress, capturing, onShutter)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                PageStack(onReview)
            }
        }
    }
}

@Composable
private fun ShutterButton(auto: Boolean, progress: Float, capturing: Boolean, onClick: () -> Unit) {
    val ring = Brand.Highlighter
    Box(
        Modifier
            .size(80.dp)
            .semantics { contentDescription = "Take photo" }
            .pressable(enabled = !capturing, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 4.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.3f), radius = size.minDimension / 2 - sw / 2, style = Stroke(sw))
            if (auto && progress > 0f) {
                drawArc(
                    ring,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(sw / 2, sw / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        val inner by animateDpAsState(if (capturing) 52.dp else 64.dp, label = "inner")
        Box(
            Modifier
                .size(inner)
                .clip(CircleShape)
                .background(if (auto) Color.White else Brand.Highlighter),
            contentAlignment = Alignment.Center,
        ) {
            if (auto) {
                Text("A", style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
            }
        }
    }
}

@Composable
private fun PageStack(onReview: () -> Unit) {
    val session = LatchApp.instance.session
    val count = session.pages.size
    val last = session.pages.lastOrNull()
    AnimatedVisibility(count > 0 || session.busy > 0, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        Box(
            Modifier
                .semantics { contentDescription = "Review $count pages" }
                .pressable(onClick = onReview),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 46.dp, height = 60.dp)
                    .rotate(8f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.35f)),
            )
            Box(
                Modifier
                    .size(width = 46.dp, height = 60.dp)
                    .rotate(-4f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (last != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(session.file(last.processed))
                            .memoryCacheKey(last.id + last.rev)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (session.busy > 0) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Brand.HighlighterDeep, strokeWidth = 2.dp)
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 10.dp, y = (-10).dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Brand.Highlighter),
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", style = MaterialTheme.typography.labelMedium, color = Brand.Ink)
            }
        }
    }
}
