package com.pntapps.pocketserver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pntapps.pocketserver.linux.SharedStorage
import com.pntapps.pocketserver.linux.SharedStorage.Companion.gb
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.ui.components.HudButton
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.components.SegmentBar
import com.pntapps.pocketserver.ui.components.StatusChip
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SD card storage for the media folders in /shared. State lives in [SharedStorage]. */
@Composable
fun StoragePanel(manager: AppManager, modifier: Modifier = Modifier) {
    val storage = manager.paths.storage
    val config by storage.config.collectAsStateWithLifecycle()
    val move by storage.move.collectAsStateWithLifecycle()
    val apps by manager.apps.collectAsStateWithLifecycle()
    // Re-read on open, after every move and on Rescan (no polling: a card change is rare).
    var scan by remember { mutableIntStateOf(0) }
    var card by remember { mutableStateOf<SharedStorage.Card?>(null) }
    var sizes by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    val moving = move is SharedStorage.Move.Working
    LaunchedEffect(scan, config, moving) {
        withContext(Dispatchers.IO) {
            card = storage.card()
            sizes = SharedStorage.FOLDERS.associateWith { storage.sizeOf(it) }
        }
    }
    val running = apps.values.any { it.active }
    val problem = remember(card, config) { storage.problem() }

    HudPanel(
        modifier,
        title = "Storage",
        trailing = {
            when {
                moving -> StatusChip("Moving", Hud.Gold, pulse = true)
                problem != null -> StatusChip("Card missing", Hud.Red)
                card != null -> StatusChip("SD card ready", Hud.Green)
                else -> StatusChip("No SD card", Hud.TextDim)
            }
        },
    ) {
        val c = card
        if (c == null) {
            Text(
                "Insert a microSD card to keep movies, music, books and downloads on it. " +
                    "Ubuntu and app data always stay on this device.",
                style = HudType.BodyDim,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.label.uppercase(), style = HudType.Label.copy(color = Hud.Cyan), modifier = Modifier.weight(1f))
                Text("${gb(c.freeBytes)} free of ${gb(c.totalBytes)}", style = HudType.Readout.copy(fontSize = 12.sp, color = Hud.TextDim))
            }
            Spacer(Modifier.height(6.dp))
            SegmentBar(1f - c.freeBytes.toFloat() / c.totalBytes.coerceAtLeast(1), Modifier.fillMaxWidth())
        }
        problem?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = HudType.Body.copy(color = Hud.Red, fontSize = 13.sp))
        }
        Spacer(Modifier.height(12.dp))

        // Phones: the Device/SD buttons get their own line under the folder name.
        val stackButtons = LocalConfiguration.current.screenWidthDp < 480
        SharedStorage.FOLDERS.forEach { folder ->
            val onSd = folder in config.onSd
            val info = @Composable { m: Modifier ->
                Column(m) {
                    Text(folder, style = HudType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // The size is its own non-wrapping Text, so "0 MB" never splits across lines.
                    val dim = HudType.Readout.copy(fontSize = 12.sp, color = Hud.TextDim)
                    Row {
                        Text(
                            "/shared/$folder" + (if (onSd && c == null) " (SD)" else "") + " · ",
                            style = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(sizes[folder]?.let(::gb) ?: "—", style = dim, maxLines = 1, softWrap = false)
                    }
                }
            }
            if (c == null) {
                // No card: nothing to choose between, so only name and size.
                info(Modifier.fillMaxWidth().padding(vertical = 3.dp))
                return@forEach
            }
            val canMove = !moving && !running && problem == null
            val buttons = @Composable {
                // The current place stays lit (selected), never greyed out like a disabled button.
                HudButton("Device", { if (onSd && canMove) manager.moveSharedFolder(folder, toSd = false) }, enabled = !onSd || canMove, filled = !onSd)
                Spacer(Modifier.width(8.dp))
                HudButton("SD card", { if (!onSd && canMove) manager.moveSharedFolder(folder, toSd = true) }, enabled = onSd || canMove, filled = onSd)
            }
            if (stackButtons) {
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    info(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row { buttons() }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    info(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    buttons()
                }
            }
        }

        when (val m = move) {
            is SharedStorage.Move.Working -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Moving ${m.folder} ${if (m.toSd) "to the SD card" else "to this device"} · ${gb(m.doneBytes)} of ${gb(m.totalBytes)}",
                    style = HudType.Body.copy(color = Hud.Gold),
                )
                Spacer(Modifier.height(6.dp))
                SegmentBar(if (m.totalBytes == 0L) 1f else m.doneBytes.toFloat() / m.totalBytes, Modifier.fillMaxWidth(), color = Hud.Gold)
            }
            is SharedStorage.Move.Failed -> MoveResult(m.message, Hud.Red) { storage.dismissMove() }
            is SharedStorage.Move.Done -> MoveResult(m.message, Hud.Green) { storage.dismissMove() }
            SharedStorage.Move.Idle -> {}
        }

        Spacer(Modifier.height(12.dp))
        if (running && c != null) Text("Stop all apps to move folders.", style = HudType.Body.copy(color = Hud.Gold, fontSize = 13.sp))
        Text("▸ Only media files go on the card: SD cards can't hold Linux permissions or databases.", style = HudType.BodyDim)
        Text("▸ Playing from the card is fine; big copies and downloads are slower than on this device.", style = HudType.BodyDim)
        Text("▸ Keep Downloads on the same storage as Movies and Shows, or finished downloads are copied twice.", style = HudType.BodyDim)
        Text("▸ The card folder is PocketServer's own: uninstalling the app deletes it too. Back up first.", style = HudType.BodyDim)
        Text("▸ Removing the card stops every app until it is back.", style = HudType.BodyDim)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            HudButton("Rescan", { scan++ }, enabled = !moving)
        }
    }
}

@Composable
private fun MoveResult(message: String, color: androidx.compose.ui.graphics.Color, onDismiss: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = HudType.Body.copy(color = color, fontSize = 13.sp), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        HudButton("OK", onDismiss, accent = color)
    }
}
