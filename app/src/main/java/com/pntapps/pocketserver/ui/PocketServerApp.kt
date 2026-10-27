package com.pntapps.pocketserver.ui

import android.os.SystemClock
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pntapps.pocketserver.linux.RootfsInstaller
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.server.SystemState
import com.pntapps.pocketserver.ui.components.ArcReactor
import com.pntapps.pocketserver.ui.components.HudButton
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.components.SegmentBar
import com.pntapps.pocketserver.ui.components.LocalRevealEpoch
import com.pntapps.pocketserver.ui.components.hudLiveBackground
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType

enum class Section(val label: String, val icon: ImageVector) {
    Core("Core", Icons.Outlined.Hub),
    Apps("Apps", Icons.Outlined.Apps),
    Terminal("Shell", Icons.Outlined.Terminal),
    Logs("Logs", Icons.Outlined.Article),
    System("System", Icons.Outlined.Settings),
}

@Composable
fun PocketServerApp(manager: AppManager) {
    val system by manager.system.collectAsState()
    Box(Modifier.fillMaxSize().hudLiveBackground()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (system is SystemState.Ready) Shell(manager) else SetupScreen(system, manager::installSystem)
        }
        if (!BootGate.shownThisProcess) BootSequence {}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Shell(manager: AppManager) {
    var section by rememberSaveable { mutableStateOf(Section.Core) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val content: @Composable (Modifier) -> Unit = { mod ->
            AnimatedContent(section, mod, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "section") {
                // A new epoch each time a screen appears: its panels reveal once, not on every scroll.
                val epoch = remember { SystemClock.uptimeMillis() }
                CompositionLocalProvider(LocalRevealEpoch provides epoch) {
                    when (it) {
                        Section.Core -> DashboardScreen(manager, wide) { section = Section.Apps }
                        Section.Apps -> AppsScreen(manager, wide)
                        Section.Terminal -> TerminalScreen(manager)
                        Section.Logs -> LogsScreen(manager)
                        Section.System -> SystemScreen(manager)
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    // Scrolls when the on-screen keyboard leaves too little height for every item.
                    Modifier.fillMaxHeight().width(96.dp).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("PS", style = HudType.Title.copy(color = Hud.Gold, fontSize = 20.sp))
                    Spacer(Modifier.height(18.dp))
                    Section.entries.forEach { NavItem(it, it == section, onClick = { section = it }) }
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(Hud.Line))
                content(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth())
                // Hidden while the keyboard is up, so the terminal and passphrase fields get the room.
                if (!WindowInsets.isImeVisible) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.Line))
                    // Equal-width items: the whole bar is tappable, no dead zones between tabs.
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Section.entries.forEach { NavItem(it, it == section, Modifier.weight(1f), bottomBar = true) { section = it } }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(section: Section, selected: Boolean, modifier: Modifier = Modifier, bottomBar: Boolean = false, onClick: () -> Unit) {
    val color = if (selected) Hud.Cyan else Hud.TextDim
    // Bottom bar (phones): 10 sp labels, growing at most to 12 dp with the system font scale so
    // "SYSTEM" still fits a fifth of a 360 dp screen. The tablet rail keeps its original look.
    val labelSize = if (bottomBar) with(LocalDensity.current) { minOf(10.sp.toDp(), 12.dp).toSp() } else 9.sp
    Column(
        modifier
            .clickable(role = Role.Tab, onClick = onClick)
            .background(if (selected) Hud.Cyan.copy(alpha = 0.08f) else Hud.Void.copy(alpha = 0f))
            .padding(horizontal = if (bottomBar) 2.dp else 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(section.icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            section.label.uppercase(), style = HudType.Label.copy(color = color, fontSize = labelSize, letterSpacing = if (bottomBar) 1.sp else HudType.Label.letterSpacing),
            maxLines = 1, softWrap = false,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(24.dp).height(2.dp).background(if (selected) Hud.Gold else Hud.Void.copy(alpha = 0f)))
    }
}

@Composable
private fun SetupScreen(state: SystemState, onInstall: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ArcReactor(online = 0, total = 0, modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth())
        Spacer(Modifier.height(28.dp))
        Text("POCKETSERVER", style = HudType.Title.copy(fontSize = 30.sp, letterSpacing = 6.sp))
        Spacer(Modifier.height(6.dp))
        Text(
            "Your own home server, running quietly on this device.\nNo root. Local network only. Uninstall removes everything.",
            style = HudType.BodyDim, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        HudPanel(Modifier.widthIn(max = 520.dp).fillMaxWidth(), title = "Core system") {
            when (state) {
                is SystemState.Installing -> {
                    val p = state.progress
                    val (label, fraction) = when (p) {
                        is RootfsInstaller.Progress.Downloading ->
                            "Downloading Ubuntu ${p.bytes / 1_048_576} / ${p.total / 1_048_576} MB" to
                                (if (p.total > 0) p.bytes.toFloat() / p.total else 0f) * 0.7f
                        is RootfsInstaller.Progress.Extracting -> "Unpacking · ${p.entries} files" to 0.7f + (p.entries / 3500f).coerceAtMost(1f) * 0.25f
                        is RootfsInstaller.Progress.Configuring -> "Configuring · ${p.step}" to 0.97f
                        null -> "Connecting" to 0f
                    }
                    Text(label, style = HudType.Readout)
                    Spacer(Modifier.height(10.dp))
                    SegmentBar(fraction)
                }
                is SystemState.Failed -> {
                    Text(state.message, style = HudType.Body.copy(color = Hud.Red))
                    Spacer(Modifier.height(12.dp))
                    HudButton("Retry", onInstall, accent = Hud.Gold, filled = true)
                }
                else -> {
                    Text("Installs Ubuntu 24.04 (about 30 MB download) inside the app's private storage.", style = HudType.BodyDim)
                    Spacer(Modifier.height(14.dp))
                    HudButton("Initialize", onInstall, accent = Hud.Gold, filled = true)
                }
            }
        }
    }
}
