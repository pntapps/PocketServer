package com.pntapps.pocketserver.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.pntapps.pocketserver.ui.components.ScrambleText
import com.pntapps.pocketserver.ui.components.chamferShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.pntapps.pocketserver.catalog.Catalog
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.ui.components.HudButton
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

/** Live tail of each app's log; refreshes only while visible. */
@Composable
fun LogsScreen(manager: AppManager) {
    var selected by rememberSaveable { mutableStateOf(Catalog.apps.first().id) }
    var lines by remember { mutableStateOf(emptyList<String>()) }
    val listState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(selected, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val next = withContext(Dispatchers.IO) { tail(manager.logFile(selected), 64 * 1024) }
                if (next != lines) {
                    lines = next
                    if (next.isNotEmpty()) listState.scrollToItem(next.lastIndex)
                }
                delay(1500)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val narrow = maxWidth < 600.dp
    Column(Modifier.fillMaxSize().padding(if (narrow) 16.dp else 20.dp)) {
        ScrambleText("SYSTEM LOGS", HudType.Title)
        Spacer(Modifier.height(12.dp))
        // Only apps that wrote a log. Wide screens: wrapping rows of chips (a scrolling row hid most of
        // ~20 apps with no hint it scrolled). Phones: one selector that opens a list, so no chip row gets cut.
        val withLogs = Catalog.apps.filter { it.id == selected || manager.logFile(it.id).exists() }
        if (narrow) {
            LogPicker(withLogs.map { it.id to it.name }, selected) { selected = it }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                withLogs.forEach { app ->
                    HudButton(app.name, { selected = app.id }, filled = app.id == selected)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        HudPanel(Modifier.fillMaxWidth().weight(1f), title = "${Catalog.byId(selected).name} output") {
            if (lines.isEmpty()) Text("No output yet.", style = HudType.BodyDim)
            LazyColumn(state = listState) {
                items(lines) { line ->
                    Text(
                        line,
                        style = HudType.Readout.copy(
                            fontSize = 12.sp,
                            color = when {
                                line.startsWith("::step::") -> Hud.Gold
                                Regex("(?i)\\b(error|fail|fatal|panic)").containsMatchIn(line) -> Hud.Red
                                else -> Hud.Text.copy(alpha = 0.85f)
                            },
                        ),
                    )
                }
            }
        }
    }
    }
}

/** Phone selector: the current app as a filled button; tapping it lists every app that has a log. */
@Composable
private fun LogPicker(apps: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val others = apps.size - 1
    Box {
        HudButton(
            Catalog.byId(selected).name + if (others > 0) "  ▾" else "",
            { if (others > 0) open = true },
            filled = true,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = chamferShape(5.dp),
            containerColor = Hud.PanelSolid,
            border = BorderStroke(1.dp, Hud.Cyan.copy(alpha = 0.55f)),
        ) {
            apps.forEach { (id, name) ->
                val current = id == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (current) "▸ " else "  ") + name.uppercase(),
                            style = HudType.Label.copy(fontSize = 12.sp, color = if (current) Hud.Text else Hud.Cyan),
                        )
                    },
                    onClick = { open = false; onSelect(id) },
                )
            }
        }
    }
}

/** Last [maxBytes] of a file, as lines (reading the whole log every refresh would waste I/O). */
private fun tail(file: java.io.File, maxBytes: Int): List<String> = runCatching {
    // The log can be rotated or truncated between length() and the read: treat that as "try again".
    tailOrThrow(file, maxBytes)
}.getOrDefault(emptyList())

private fun tailOrThrow(file: java.io.File, maxBytes: Int): List<String> {
    if (!file.exists()) return emptyList()
    RandomAccessFile(file, "r").use { raf ->
        val start = (raf.length() - maxBytes).coerceAtLeast(0)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.readFully(bytes)
        return String(bytes).lines().let { if (start > 0) it.drop(1) else it }.dropLastWhile { it.isEmpty() }
    }
}
