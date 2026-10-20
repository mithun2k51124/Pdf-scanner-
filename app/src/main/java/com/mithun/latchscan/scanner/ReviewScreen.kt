package com.mithun.latchscan.scanner

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mithun.latchscan.LatchApp
import com.mithun.latchscan.ui.BusyOverlay
import com.mithun.latchscan.ui.PrimaryButton
import com.mithun.latchscan.ui.RoundIconButton
import com.mithun.latchscan.ui.theme.Brand
import com.mithun.latchscan.ui.theme.Latch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun pagesLabel(n: Int) = if (n == 1) "1 page" else "$n pages"

fun defaultScanName(): String = "Scan " + SimpleDateFormat("d MMM yyyy, HH.mm", Locale.getDefault()).format(Date())

@Composable
fun ReviewScreen(
    onBackToCamera: () -> Unit,
    onCrop: (pageId: String) -> Unit,
    onSaved: (docId: String) -> Unit,
) {
    val app = LatchApp.instance
    val session = app.session
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = session.targetDocId?.let { app.repository.get(it) }
    var name by remember { mutableStateOf(defaultScanName()) }
    var saving by remember { mutableStateOf(false) }

    BackHandler(onBack = onBackToCamera)

    fun save() {
        if (saving || session.pages.isEmpty()) return
        saving = true
        scope.launch {
            val result = runCatching {
                val pages = session.finalPages()
                if (target != null) {
                    app.repository.appendPages(target.id, pages, session.dir)
                } else {
                    app.repository.saveNew(name.trim().ifEmpty { defaultScanName() }, pages, session.dir)
                }
            }
            // UI work always back on the main thread.
            withContext(Dispatchers.Main) {
                saving = false
                result.onSuccess { doc ->
                    session.clear()
                    if (doc != null) onSaved(doc.id)
                }.onFailure {
                    Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    PagesEditor(
        host = session,
        title = if (target != null) "Add to ${target.name}" else "Review scan",
        onBack = onBackToCamera,
        onCrop = { onCrop(it.id) },
        headerActions = {
            RoundIconButton(Icons.Rounded.AddAPhoto, "Scan more pages", onBackToCamera)
        },
        extraContent = {
            if (session.mode == CaptureMode.FRONT_BACK && session.pages.size >= 2) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Latch.colors.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Front & back on one page", style = MaterialTheme.typography.titleSmall, color = Latch.colors.text)
                        Text("Good for ID cards and receipts", style = MaterialTheme.typography.bodySmall, color = Latch.colors.textMuted)
                    }
                    Switch(
                        checked = session.combineFrontBack,
                        onCheckedChange = { session.combineFrontBack = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Brand.Highlighter, checkedThumbColor = Brand.Ink),
                    )
                }
            }
        },
        bottomBar = {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                if (target == null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(80) },
                        label = { Text("File name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.HighlighterDeep, focusedLabelColor = Latch.colors.text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = (if (target != null) "Add " else "Save ") + pagesLabel(session.pages.size),
                        onClick = { save() },
                        icon = Icons.Rounded.Check,
                        enabled = session.pages.isNotEmpty() && session.busy == 0,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.width(1.dp))
            }
        },
    )
    BusyOverlay(saving, "Saving…")
    }
}
