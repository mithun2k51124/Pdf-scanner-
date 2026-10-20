package com.mithun.latchscan.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mithun.latchscan.ui.theme.Brand
import com.mithun.latchscan.ui.theme.Latch
import com.mithun.latchscan.util.Files
import java.io.File

/** Saves a finished file: straight to Downloads on Android 10+, otherwise via "Save as". */
class FileSaver(private val save: (File) -> Unit) {
    fun invoke(file: File) = save(file)
}

@Composable
fun rememberFileSaver(): FileSaver {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<File?>(null) }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pending
        pending = null
        if (uri != null && f != null) {
            runCatching { Files.writeTo(context, f, uri) }
                .onSuccess { Toast.makeText(context, "Saved ${f.name}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    return remember(context) {
        FileSaver { file ->
            if (Files.canQuickSave) {
                runCatching { Files.saveToDownloads(context, file) }
                    .onSuccess { Toast.makeText(context, "Saved to $it", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
            } else {
                pending = file
                createDoc.launch(file.name)
            }
        }
    }
}

/** Card shown when a tool finishes: what was made, and what to do with it. */
@Composable
fun ResultPanel(
    files: List<File>,
    headline: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val saver = rememberFileSaver()
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(files) {
        pop.snapTo(0.6f)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Latch.colors.surface)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .clip(CircleShape)
                    .background(Brand.Highlighter),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, null, tint = Brand.Ink) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleMedium, color = Latch.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Latch.colors.textMuted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files.size == 1) {
                SecondaryButton("Open", { Files.open(context, files[0]) }, Modifier.weight(1f), icon = Icons.Rounded.OpenInNew)
            }
            SecondaryButton("Save", { files.forEach { saver.invoke(it) } }, Modifier.weight(1f), icon = Icons.Rounded.Download, enabled = files.size == 1 || Files.canQuickSave)
            SecondaryButton("Share", { Files.share(context, files) }, Modifier.weight(1f), icon = Icons.Rounded.Share)
        }
    }
}
