package com.pntapps.pocketserver.ui

import com.pntapps.pocketserver.ui.components.hudFrame
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import android.os.PersistableBundle
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.ClipData
import com.pntapps.pocketserver.ui.components.hudReveal
import com.pntapps.pocketserver.ui.components.ScrambleText
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pntapps.pocketserver.catalog.Catalog
import com.pntapps.pocketserver.catalog.CatalogApp
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.server.AppPhase
import com.pntapps.pocketserver.server.AppState
import com.pntapps.pocketserver.ui.components.AppHologram
import com.pntapps.pocketserver.ui.components.HologramState
import com.pntapps.pocketserver.ui.components.HudButton
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.components.QrCode
import com.pntapps.pocketserver.ui.components.StatusChip
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.pntapps.pocketserver.ui.components.chamferPath
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AppsScreen(manager: AppManager, wide: Boolean) {
    val apps by manager.apps.collectAsState()
    val context = LocalContext.current
    val deviceRamMb = remember {
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem / 1_048_576
    }
    // Staggered: cards differ a lot in height, so each column packs tightly instead of leaving gaps.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Same count the Adaptive(360.dp) grid below arrives at (minus its padding, plus one spacing).
    val columns = maxOf(1, ((maxWidth - (if (wide) 56.dp else 32.dp) + 20.dp) / 380.dp).toInt())
    // Phone widths (or a large system font on a phone) get the compact card; tablets keep the roomy one.
    val compact = !wide && (maxWidth < 400.dp || (LocalDensity.current.fontScale >= 1.2f && maxWidth < 600.dp))
    // Narrow pack headers stack the button under the description so the text gets the full width.
    val stackHeader = maxWidth < 480.dp
    LazyVerticalStaggeredGrid(
        StaggeredGridCells.Adaptive(360.dp),
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(if (wide) 28.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalItemSpacing = 20.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Column {
                ScrambleText("APP ARSENAL", HudType.Title)
                Text("One tap installs each app with battery-saving settings already applied.", style = HudType.BodyDim)
            }
        }
        items(Catalog.apps.filter { it.group == null }, key = { it.id }) { app ->
            AppCard(app, apps.getValue(app.id), manager, tooWeak = deviceRamMb < app.minRamMb, compact = compact)
        }
        (Catalog.packs.keys + Catalog.apps.mapNotNull { it.group }).distinct().forEach { group ->
            val members = Catalog.apps.filter { it.group == group }
            if (members.isEmpty()) return@forEach
            item(key = "group-$group", span = StaggeredGridItemSpan.FullLine) {
                GroupHeader(group, members, apps, manager, stacked = stackHeader)
            }
            // An odd last card takes the whole row instead of leaving half of it empty.
            val lastSpans = columns > 1 && members.size % columns == 1
            items(
                members, key = { it.id },
                span = { if (lastSpans && it == members.last()) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane },
            ) { app ->
                AppCard(app, apps.getValue(app.id), manager, tooWeak = deviceRamMb < app.minRamMb, compact = compact)
            }
        }
    }
    }
}

/** Heading for a bundle such as the Web Dev Pack: what it contains, and one button to install it all. */
@Composable
private fun GroupHeader(group: String, members: List<CatalogApp>, apps: Map<String, AppState>, manager: AppManager, stacked: Boolean) {
    val missing = members.filter { apps[it.id]?.installed != true }
    val busy = members.any { apps[it.id]?.phase is AppPhase.Installing }
    val info: @Composable () -> Unit = {
        Text(Catalog.packs[group] ?: "", style = HudType.BodyDim)
        Spacer(Modifier.height(6.dp))
        Text(
            "${members.size - missing.size} of ${members.size} installed" +
                if (missing.isEmpty()) "" else " · about ${missing.sumOf { it.sizeMb }} MB to download",
            style = HudType.Readout.copy(fontSize = 12.sp, color = Hud.TextDim),
        )
    }
    val action: @Composable () -> Unit = {
        when {
            busy -> StatusChip("Installing", Hud.Gold, pulse = true)
            missing.isEmpty() -> StatusChip("Pack ready", Hud.Green)
            else -> HudButton("Install whole pack", { manager.installGroup(group) }, accent = Hud.Gold, filled = true)
        }
    }
    HudPanel(Modifier.fillMaxWidth().padding(top = 12.dp), title = group, accent = Hud.Gold) {
        if (stacked) {
            info()
            Spacer(Modifier.height(10.dp))
            action()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { info() }
                Spacer(Modifier.width(16.dp))
                action()
            }
        }
    }
}

@Composable
private fun AppCard(app: CatalogApp, state: AppState, manager: AppManager, tooWeak: Boolean, compact: Boolean) {
    val context = LocalContext.current
    val phase = state.phase
    var qrUrl by remember { mutableStateOf<String?>(null) }
    qrUrl?.let { url -> QrDialog(app.name, url) { qrUrl = null } }
    val (chip, color) = when (phase) {
        AppPhase.NotInstalled -> "Not installed" to Hud.TextDim
        is AppPhase.Installing -> "Deploying" to Hud.Gold
        AppPhase.Stopped -> if (app.service) "Offline" to Hud.CyanDim else "Ready" to Hud.Green
        AppPhase.Starting -> "Booting" to Hud.Gold
        is AppPhase.Running -> "Online" to Hud.Green
        AppPhase.Stopping -> "Shutting down" to Hud.Gold
        is AppPhase.Failed -> "Fault" to Hud.Red
    }
    val accent = when (phase) {
        is AppPhase.Running -> Hud.Green
        is AppPhase.Failed -> Hud.Red
        else -> Hud.Cyan
    }
    HudPanel(Modifier.fillMaxWidth().hudReveal(Catalog.apps.indexOf(app)), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppHologram(
                app.id,
                when (phase) {
                    is AppPhase.Running -> HologramState.Online
                    is AppPhase.Failed -> HologramState.Fault
                    is AppPhase.Installing, AppPhase.Starting, AppPhase.Stopping -> HologramState.Busy
                    else -> HologramState.Offline
                },
                Modifier.size(if (compact) 64.dp else 84.dp),
            )
            Spacer(Modifier.width(if (compact) 12.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                // One line, never split mid-word ("AUDIOBOOKSH/ELF"): long names shrink to fit instead.
                BasicText(
                    app.name.uppercase(),
                    style = HudType.Title.copy(fontSize = 18.sp, letterSpacing = if (compact) 1.5.sp else 3.sp),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 18.sp),
                )
                Text(app.tagline, style = HudType.BodyDim)
            }
        }
        Spacer(Modifier.height(12.dp))
        StatusLine(
            { StatusChip(chip, color, pulse = phase is AppPhase.Installing || phase == AppPhase.Starting || phase is AppPhase.Running) },
            if (app.service) listOf("PORT ${app.port}", "~${app.typicalRamMb} MB RAM", "${app.sizeMb} MB") else listOf("TOOLS", "${app.sizeMb} MB"),
        )

        when (phase) {
            is AppPhase.Installing -> {
                Spacer(Modifier.height(10.dp))
                Text("› ${phase.step}", style = HudType.Readout.copy(color = Hud.Gold))
            }
            is AppPhase.Failed -> {
                Spacer(Modifier.height(10.dp))
                Text(phase.message, style = HudType.Body.copy(color = Hud.Red, fontSize = 14.sp), maxLines = 3)
            }
            is AppPhase.Running -> {
                Spacer(Modifier.height(10.dp))
                if (app.localOnly) {
                    val ip = manager.lanAddresses().firstOrNull()
                    Text(
                        if (state.lan && ip != null) "$ip:${app.port} · Wi-Fi devices (login required)"
                        else "127.0.0.1:${app.port} · this device only",
                        style = HudType.Readout,
                    )
                } else {
                    val ip = manager.lanAddresses().firstOrNull()
                    if (ip != null) Text(app.url(ip), style = HudType.Readout)
                }
            }
            else -> {}
        }

        Spacer(Modifier.height(12.dp))
        Text("OPTIMIZATIONS", style = HudType.Label.copy(fontSize = 10.sp, color = Hud.TextDim))
        app.notes.forEach { Text("▸ $it", style = HudType.BodyDim.copy(fontSize = 13.sp)) }

        if (app.lanToggle && state.installed) {
            Spacer(Modifier.height(12.dp))
            Text("WI-FI ACCESS", style = HudType.Label.copy(fontSize = 10.sp, color = Hud.TextDim))
            Spacer(Modifier.height(6.dp))
            // Flow: with a large font the second button moves to its own line instead of wrapping its label.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("This device", { manager.setLanAccess(app.id, false) }, filled = !state.lan)
                HudButton(if (compact) "Wi-Fi" else "Wi-Fi devices", { manager.setLanAccess(app.id, true) }, accent = Hud.Gold, filled = state.lan)
            }
            if (state.lan) {
                Spacer(Modifier.height(4.dp))
                Text("Any device on this Wi-Fi can connect with the password below.", style = HudType.BodyDim.copy(fontSize = 13.sp, color = Hud.Gold))
            }
        }
        if (app.id == AppManager.VAULT && state.installed) VaultFirstAccount(manager, phase, compact)
        if (state.installed && state.credentials.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Credentials(state.credentials)
        }
        if (tooWeak && !state.installed) {
            Spacer(Modifier.height(10.dp))
            Text("Not recommended on this device: needs ${app.minRamMb / 1024} GB+ RAM.", style = HudType.Body.copy(color = Hud.Gold, fontSize = 14.sp))
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (phase) {
                AppPhase.NotInstalled -> HudButton("Install", { manager.install(app.id) }, accent = Hud.Gold, filled = true, enabled = !tooWeak)
                is AppPhase.Failed -> if (state.installed) {
                    HudButton("Restart", { manager.start(app.id) }, accent = Hud.Gold, filled = true)
                } else {
                    HudButton("Retry install", { manager.install(app.id) }, accent = Hud.Gold, filled = true)
                }
                AppPhase.Stopped -> if (app.service) HudButton("Start", { manager.start(app.id) }, accent = Hud.Green, filled = true)
                is AppPhase.Running -> {
                    // Databases speak their own protocol: nothing to open in a browser or share by QR.
                    if (!app.localOnly) {
                        HudButton("Open", {
                            // No browser installed (rare on tablets, possible on stripped ROMs): say so instead of crashing.
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.localUrl))) }
                                .onFailure { android.widget.Toast.makeText(context, "No browser found to open ${app.localUrl}", android.widget.Toast.LENGTH_LONG).show() }
                        }, accent = Hud.Cyan, filled = true)
                        val ip = manager.lanAddresses().firstOrNull()
                        if (ip != null) HudButton("QR", { qrUrl = app.url(ip) }, accent = Hud.Cyan)
                    }
                    HudButton("Stop", { manager.stop(app.id) }, accent = Hud.Red)
                }
                else -> HudButton("Working…", {}, enabled = false)
            }
        }
    }
}

/**
 * Vaultwarden's invite-only first account: sign-ups never open; the vault invites exactly the email
 * entered here (start.sh), and the card follows its first-account status file.
 */
@Composable
private fun VaultFirstAccount(manager: AppManager, phase: AppPhase, compact: Boolean) {
    var status by remember { mutableStateOf(manager.appFile(AppManager.VAULT, "first-account")) }
    var owner by remember { mutableStateOf(manager.appFile(AppManager.VAULT, "owner-email").orEmpty()) }
    var draft by remember { mutableStateOf(owner) }
    // The status changes inside the running vault (account created): re-read it while this card is on screen.
    LaunchedEffect(phase) {
        while (true) {
            status = manager.appFile(AppManager.VAULT, "first-account")
            if (phase !is AppPhase.Running || status == "done") break
            delay(3000)
        }
    }
    if (status == "done") return
    val valid = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(draft.trim())
    Spacer(Modifier.height(12.dp))
    Text("FIRST ACCOUNT", style = HudType.Label.copy(fontSize = 10.sp, color = Hud.Gold))
    Spacer(Modifier.height(4.dp))
    Text(
        when {
            owner.isEmpty() -> "Sign-ups are closed to everyone. Enter the email you will use for your vault: only that address can create the account."
            phase is AppPhase.Running -> "Open the vault and choose Create account with $owner. Nobody else can sign up."
            else -> "Start the vault, then create the account with $owner. Nobody else can sign up."
        },
        style = HudType.BodyDim.copy(fontSize = 13.sp),
    )
    Spacer(Modifier.height(8.dp))
    val field: @Composable (Modifier) -> Unit = { mod ->
        BasicTextField(
            value = draft,
            onValueChange = { draft = it.take(254) },
            singleLine = true,
            textStyle = HudType.Readout.copy(color = Hud.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(Hud.Gold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            modifier = mod
                .hudFrame(Hud.Gold.copy(alpha = 0.7f), cut = 6.dp, fill = Hud.Deep)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) Text("you@example.com", style = HudType.Readout.copy(color = Hud.TextDim, fontSize = 15.sp))
                    inner()
                }
            },
        )
    }
    val allow: @Composable (Modifier) -> Unit = { mod ->
        HudButton(
            if (owner.isEmpty()) "Allow" else "Change",
            { manager.setVaultOwner(draft.trim()); owner = draft.trim() },
            mod, accent = Hud.Gold, filled = true, enabled = valid && draft.trim() != owner,
        )
    }
    // Phones: the address gets the full width and the button sits under it.
    if (compact) {
        Column {
            field(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            allow(Modifier.align(Alignment.End))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            field(Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            allow(Modifier)
        }
    }
}

/**
 * Status chip followed by readout tokens ("PORT 8096", "~250 MB RAM", ...). Tokens never split, and the
 * " · " separator is drawn only between tokens on the same line, so a wrap never leaves one dangling.
 */
@Composable
private fun StatusLine(chip: @Composable () -> Unit, tokens: List<String>) {
    val style = HudType.Readout.copy(fontSize = 12.sp, color = Hud.TextDim)
    Layout(content = {
        chip()
        tokens.forEach { Text(it, style = style, maxLines = 1, softWrap = false) }
        repeat(tokens.size - 1) { Text(" · ", style = style, maxLines = 1, softWrap = false) }
    }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val all = measurables.map { it.measure(loose) }
        val words = all.subList(1, 1 + tokens.size)
        val seps = all.subList(1 + tokens.size, all.size)
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val gap = 10.dp.roundToPx()
        val lineGap = 4.dp.roundToPx()
        // (placeable, x, line); the chip opens the first line.
        val placed = mutableListOf(Triple(all[0], 0, 0))
        val lineHeights = mutableListOf(all[0].height)
        var x = all[0].width
        words.forEachIndexed { i, word ->
            val sep: Placeable? = if (i > 0) seps[i - 1] else null
            val lead = sep?.width ?: gap
            if (x + lead + word.width > maxW && x > 0) {
                lineHeights += 0
                x = 0
            } else {
                if (sep != null) placed += Triple(sep, x, lineHeights.lastIndex)
                x += lead
            }
            placed += Triple(word, x, lineHeights.lastIndex)
            x += word.width
            lineHeights[lineHeights.lastIndex] = maxOf(lineHeights.last(), word.height)
        }
        val tops = lineHeights.runningFold(0) { top, h -> top + h + lineGap }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placed.maxOf { it.second + it.first.width }
        layout(width, tops.last() - lineGap) {
            placed.forEach { (p, px, line) -> p.place(px, tops[line] + (lineHeights[line] - p.height) / 2) }
        }
    }
}

/** Scan-to-open for other devices on the same Wi-Fi. */
@Composable
private fun QrDialog(name: String, url: String, onDismiss: () -> Unit) {
    Dialog(onDismiss) {
        // Opaque: the translucent panel let the card behind show through the text.
        // A solid chamfered backing as well, so even the frame's fading gradient never shows the card behind.
        HudPanel(Modifier.widthIn(max = 360.dp).drawBehind { drawPath(chamferPath(size, 14.dp.toPx()), Hud.PanelSolid) }, title = "Scan to open · $name", accent = Hud.Cyan, fill = Hud.PanelSolid) {
            QrCode(url, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(url, style = HudType.Readout.copy(color = Hud.Text), modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(6.dp))
            Text("Point a phone or tablet camera at the code. It must be on the same Wi-Fi as this device.", style = HudType.BodyDim)
            Spacer(Modifier.height(14.dp))
            HudButton("Close", onDismiss, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun Credentials(items: List<Pair<String, String>>) {
    var reveal by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(copied) { if (copied != null) { delay(1500); copied = null } }
    Column {
        Text(
            if (reveal) "ACCESS · TAP TO HIDE" else "ACCESS · TAP TO REVEAL",
            style = HudType.Label.copy(fontSize = 10.sp, color = Hud.Gold),
            modifier = Modifier.clickable { reveal = !reveal }.padding(vertical = 4.dp),
        )
        items.forEach { (k, v) ->
            // API keys and tokens grant full control just like passwords: hidden until revealed.
            // Connection strings embed the password (scheme://user:pass@host), so they count too.
            val secret = listOf("password", "key", "token", "secret", "connection").any { k.contains(it, ignoreCase = true) } ||
                Regex("://[^/@\\s]+:[^/@\\s]+@").containsMatchIn(v)
            // Logins and ready-to-paste commands get a copy icon; other lines are instructions.
            val copyable = secret || k.contains("user", ignoreCase = true) || k.startsWith("Run") || k.contains("connection", ignoreCase = true)
            // Same minimum height with or without the copy icon, so the list keeps an even rhythm.
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                // One text: wrapped lines return to the left edge instead of a narrow hanging column.
                // Monospace only for values meant to be pasted; prose instructions read better in Body.
                // Path lists ("/shared/Movies, /shared/Shows") get one path per line, so a narrow
                // screen never splits a path at a slash or mid-word.
                val shown = when {
                    secret && !reveal -> "••••••••"
                    ", /" in v -> "\n" + v.split(", ").joinToString(",\n")
                    else -> v
                }
                Text(
                    buildAnnotatedString {
                        withStyle(HudType.BodyDim.copy(fontSize = 13.sp).toSpanStyle()) { append("$k: ") }
                        withStyle(
                            if (copyable) HudType.Readout.copy(fontSize = 13.sp, color = Hud.Text).toSpanStyle()
                            else HudType.Body.copy(fontSize = 14.sp, color = Hud.Text).toSpanStyle()
                        ) { append(shown) }
                    },
                    style = HudType.Body.copy(fontSize = 14.sp),
                    // Fills the row so every copy icon lines up at the right edge.
                    modifier = Modifier.weight(1f).clickable { reveal = !reveal }.padding(vertical = 4.dp),
                )
                if (copyable) {
                    val done = copied == k
                    Box(
                        Modifier
                            .padding(start = 4.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClickLabel = "Copy $k") {
                                copyToClipboard(context, k, v, sensitive = secret)
                                copied = k
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (done) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = if (done) "Copied" else "Copy $k",
                            tint = if (done) Hud.Green else Hud.CyanDim,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Sensitive clips are hidden from Android's clipboard preview and keyboard suggestions (Android 13+). */
private fun copyToClipboard(context: Context, label: String, value: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText(label, value)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
