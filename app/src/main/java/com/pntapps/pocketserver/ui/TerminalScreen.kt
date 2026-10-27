package com.pntapps.pocketserver.ui

import androidx.compose.ui.platform.LocalView
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.terminal.HudTerminalViewClient
import com.pntapps.pocketserver.terminal.StickyModifiers
import com.pntapps.pocketserver.terminal.TerminalSessions
import com.pntapps.pocketserver.ui.components.HudButton
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.components.StatusChip
import com.pntapps.pocketserver.ui.components.chamferPath
import com.pntapps.pocketserver.ui.components.chamferShape
import com.pntapps.pocketserver.ui.components.hudFrame
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import com.termux.view.TerminalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A real bash login shell inside Ubuntu. Shells live in [TerminalSessions], so leaving this screen keeps them running. */
@Composable
fun TerminalScreen(manager: AppManager) {
    if (!manager.paths.isInstalled) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("TERMINAL", style = HudType.Title)
            Spacer(Modifier.height(12.dp))
            HudPanel(Modifier.fillMaxWidth(), title = "Offline", accent = Hud.Gold) {
                Text("The Ubuntu system is not installed yet. Install it first, then come back for a shell.", style = HudType.BodyDim)
            }
        }
        return
    }

    val context = LocalContext.current
    val state by TerminalSessions.state.collectAsState()
    val fontSp by TerminalSessions.fontSp.collectAsState()
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val textSizePx = with(LocalDensity.current) { (fontSp ?: if (tablet) 14 else 12).sp.toPx() }.roundToInt()
    val modifiers = remember { StickyModifiers() }
    val view = remember { mutableStateOf<TerminalView?>(null) }
    val current = state.current

    // Session buttons are Compose views and take focus; hand it back so typing reaches the shell.
    // With no shell left, close the keyboard instead of leaving it over the empty screen.
    val hostView = LocalView.current
    LaunchedEffect(state.currentId, state.shells.size) {
        val v = view.value
        if (current != null && v != null) v.post { v.requestFocus() }
        else context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(hostView.windowToken, 0)
    }

    // First visit opens a shell; the process itself starts only once the view lays it out.
    LaunchedEffect(Unit) {
        if (TerminalSessions.fontSp.value == null) TerminalSessions.fontSp.value = if (tablet) 14 else 12
        if (TerminalSessions.state.value.shells.isEmpty()) TerminalSessions.open(context, manager.paths)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TERMINAL", style = HudType.Title)
            Spacer(Modifier.weight(1f))
            when {
                current == null -> StatusChip("No shell", Hud.TextDim)
                current.running -> StatusChip("Root shell", Hud.Green)
                else -> StatusChip("Exited ${current.exitCode}", Hud.Red)
            }
        }
        Spacer(Modifier.height(10.dp))
        // Session buttons may scroll (many shells, phones); A-/A+ stay outside the scroll so they are always
        // visible. fill = false: when everything fits, the row looks exactly like one unbroken row.
        val tabsScroll = rememberScrollState()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f, fill = false)
                // Fades at the edge that has more buttons beyond it: the hint that the row scrolls.
                .drawWithContent {
                    drawContent()
                    val fade = 28.dp.toPx()
                    if (tabsScroll.canScrollForward) drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Hud.Void), startX = size.width - fade, endX = size.width),
                        topLeft = Offset(size.width - fade, 0f),
                        size = size.copy(width = fade),
                    )
                    if (tabsScroll.canScrollBackward) drawRect(
                        Brush.horizontalGradient(listOf(Hud.Void, Color.Transparent), startX = 0f, endX = fade),
                        size = size.copy(width = fade),
                    )
                }
                .horizontalScroll(tabsScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.shells.forEachIndexed { i, shell ->
                HudButton(
                    "SH ${i + 1}" + if (shell.running) "" else " ×",
                    { TerminalSessions.select(shell.id) },
                    accent = if (shell.running) Hud.Cyan else Hud.Red,
                    filled = shell.id == state.currentId,
                )
            }
            HudButton("+ New", { TerminalSessions.open(context, manager.paths) }, accent = Hud.Gold)
            if (current != null) {
                if (!current.running) HudButton("Restart", { TerminalSessions.restart(current.id, manager.paths) }, accent = Hud.Gold)
                HudButton("Close", { TerminalSessions.close(current.id) }, accent = Hud.Red)
            }
        }
            HudButton("A−", { TerminalSessions.changeFont(-1) })
            HudButton("A+", { TerminalSessions.changeFont(1) })
        }
        Spacer(Modifier.height(12.dp))

        Box(Modifier.fillMaxWidth().weight(1f).hudFrame(cut = 10.dp, fill = Hud.Void).padding(10.dp)) {
            if (current == null) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("No shell running.", style = HudType.BodyDim)
                    Spacer(Modifier.height(12.dp))
                    HudButton("Open shell", { TerminalSessions.open(context, manager.paths) }, filled = true)
                }
            } else {
                TerminalViewHost(current.session, textSizePx, modifiers, view, manager)
            }
        }
        Spacer(Modifier.height(10.dp))
        ExtraKeys(modifiers) { view.value }
    }
}

@Composable
private fun TerminalViewHost(
    session: com.termux.terminal.TerminalSession,
    textSizePx: Int,
    modifiers: StickyModifiers,
    holder: androidx.compose.runtime.MutableState<TerminalView?>,
    manager: AppManager,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(Hud.Void.toArgb())
                // setTextSize creates the renderer; setTypeface before it dereferences a null renderer.
                setTextSize(textSizePx)
                // Typeface.MONOSPACE has the box-drawing glyphs htop/nano/mc need; ShareTech Mono does not.
                setTypeface(Typeface.MONOSPACE)
                tag = textSizePx
                setTerminalViewClient(
                    HudTerminalViewClient(
                        modifiers,
                        showKeyboard = {
                            requestFocus()
                            ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        },
                        restart = { TerminalSessions.restart(it, manager.paths) },
                    )
                )
                TerminalSessions.view = this
                holder.value = this
            }
        },
        update = { v ->
            if (v.tag != textSizePx) { v.tag = textSizePx; v.setTextSize(textSizePx) }
            if (v.currentSession !== session) v.attachSession(session)
        },
        onRelease = { v ->
            if (TerminalSessions.view === v) TerminalSessions.view = null
            if (holder.value === v) holder.value = null
        },
    )
}

private sealed interface ExtraKey {
    val label: String
    data class Code(override val label: String, val keyCode: Int, val repeat: Boolean = false) : ExtraKey
    data class Chr(override val label: String, val char: Char) : ExtraKey
    data object Ctrl : ExtraKey { override val label = "CTRL" }
    data object Alt : ExtraKey { override val label = "ALT" }
}

private val keysA = listOf(
    ExtraKey.Code("ESC", KeyEvent.KEYCODE_ESCAPE), ExtraKey.Code("TAB", KeyEvent.KEYCODE_TAB),
    ExtraKey.Ctrl, ExtraKey.Alt, ExtraKey.Chr("~", '~'), ExtraKey.Chr("|", '|'),
)
private val keysB = listOf(
    ExtraKey.Chr("-", '-'), ExtraKey.Chr("/", '/'),
    ExtraKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT, true), ExtraKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP, true),
    ExtraKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN, true), ExtraKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT, true),
)

/** One row on wide screens, two on phones, so each key stays a comfortable touch target. */
@Composable
private fun ExtraKeys(modifiers: StickyModifiers, view: () -> TerminalView?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (keysA + keysB).forEach { KeyCap(it, modifiers, view) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { keysA.forEach { KeyCap(it, modifiers, view) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { keysB.forEach { KeyCap(it, modifiers, view) } }
            }
        }
    }
}

@Composable
private fun RowScope.KeyCap(key: ExtraKey, modifiers: StickyModifiers, view: () -> TerminalView?) {
    val armed = when (key) {
        ExtraKey.Ctrl -> modifiers.ctrl
        ExtraKey.Alt -> modifiers.alt
        else -> false
    }
    val color = if (key is ExtraKey.Ctrl || key is ExtraKey.Alt) Hud.Gold else Hud.Cyan
    val press = remember { mutableStateOf(false) }
    val send: () -> Unit = {
        val v = view()
        when (key) {
            ExtraKey.Ctrl -> modifiers.ctrl = !modifiers.ctrl
            ExtraKey.Alt -> modifiers.alt = !modifiers.alt
            // Through TerminalView so sticky CTRL/ALT and the emulator's cursor-key mode apply.
            is ExtraKey.Code -> v?.onKeyDown(key.keyCode, KeyEvent(KeyEvent.ACTION_DOWN, key.keyCode))
            is ExtraKey.Chr -> v?.inputCodePoint(key.char.code, false, false)
        }
    }
    val shape = chamferShape(4.dp)
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(shape)
            .background(color.copy(alpha = if (armed || press.value) 0.30f else 0.07f))
            .drawBehind { drawPath(chamferPath(size, 4.dp.toPx() * 2.75f), color.copy(alpha = if (armed) 1f else 0.6f), style = Stroke(1.dp.toPx())) }
            .then(
                if (key is ExtraKey.Code && key.repeat) {
                    // Arrow keys auto-repeat while held; the loop exists only during the press.
                    Modifier.pointerInput(key) {
                        detectTapGestures(onPress = {
                            press.value = true
                            coroutineScope {
                                send()
                                val repeater = launch { delay(400); while (true) { send(); delay(60) } }
                                tryAwaitRelease()
                                repeater.cancel()
                            }
                            press.value = false
                        })
                    }
                } else {
                    Modifier.clickable(role = Role.Button, onClick = send)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            key.label,
            style = HudType.Readout.copy(color = if (armed) Hud.Text else color, fontSize = if (key.label.length > 1) 12.sp else 16.sp),
            textAlign = TextAlign.Center,
        )
    }
}

