package com.pntapps.pocketserver.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import com.pntapps.pocketserver.linux.LinuxPaths
import com.pntapps.pocketserver.linux.Proot
import com.pntapps.pocketserver.ui.theme.Hud
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * App-scoped holder for terminal shells, so a bash session outlives the screen that shows it.
 * Nothing here runs on its own: a session forks its process only once a [TerminalView] gives it a
 * size, and its output is processed only when bash writes some. All calls must be on the main thread
 * (TerminalSession binds its output handler to the creating thread's looper).
 */
object TerminalSessions {
    private const val TAG = "PocketTerminal"
    private const val TRANSCRIPT_ROWS = 3000

    /** One shell tab. [id] survives a restart, so the tab keeps its place. */
    class Shell(val id: Int, val session: TerminalSession) {
        val running: Boolean get() = session.isRunning
        val exitCode: Int get() = session.exitStatus
    }

    /** [revision] bumps when a shell exits, so observers re-read [Shell.running]. */
    data class State(val shells: List<Shell> = emptyList(), val currentId: Int? = null, val revision: Int = 0) {
        val current: Shell? get() = shells.firstOrNull { it.id == currentId }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Text size in sp, shared by every shell; null until the screen picks a default for the device. */
    val fontSp = MutableStateFlow<Int?>(null)
    const val MIN_FONT_SP = 7
    const val MAX_FONT_SP = 32

    /** The view currently showing a shell, if the terminal screen is on screen. Cleared on dispose. */
    var view: TerminalView? = null

    private var appContext: Context? = null
    private var nextId = 1

    fun open(context: Context, paths: LinuxPaths): Shell {
        appContext = context.applicationContext
        applyHudColors()
        val shell = Shell(nextId++, newSession(paths))
        _state.update { it.copy(shells = it.shells + shell, currentId = shell.id) }
        return shell
    }

    fun select(id: Int) = _state.update { it.copy(currentId = id) }

    /** Replaces a shell whose bash exited (or that the user wants fresh) with a new login shell. */
    fun restart(id: Int, paths: LinuxPaths) {
        val old = _state.value.shells.firstOrNull { it.id == id } ?: return
        kill(old.session)
        val fresh = Shell(id, newSession(paths))
        _state.update { s -> s.copy(shells = s.shells.map { if (it.id == id) fresh else it }, currentId = id, revision = s.revision + 1) }
    }

    fun restart(session: TerminalSession, paths: LinuxPaths) {
        _state.value.shells.firstOrNull { it.session === session }?.let { restart(it.id, paths) }
    }

    fun close(id: Int) {
        val s = _state.value
        val index = s.shells.indexOfFirst { it.id == id }
        if (index < 0) return
        kill(s.shells[index].session)
        val rest = s.shells.filterNot { it.id == id }
        val nextCurrent = if (s.currentId == id) rest.getOrNull(index.coerceAtMost(rest.lastIndex))?.id else s.currentId
        _state.value = s.copy(shells = rest, currentId = nextCurrent, revision = s.revision + 1)
    }

    /** Ends every shell (used by the overheating guard). */
    fun closeAll() = _state.value.shells.map { it.id }.forEach(::close)

    fun changeFont(delta: Int) {
        fontSp.update { (it ?: 12).plus(delta).coerceIn(MIN_FONT_SP, MAX_FONT_SP) }
    }

    /**
     * The JNI layer forks, calls setsid() on a new pty, then execvp(argv[0], argv) with exactly the
     * env we pass (it clearenv()s first). So argv is Proot's full command line - the proot binary in
     * nativeLibraryDir, the one place Android lets us exec from - and env is proot's host env. The
     * host cwd only has to exist; the guest cwd (/root) is set by proot's -w.
     */
    private fun newSession(paths: LinuxPaths): TerminalSession {
        paths.prootTmp.mkdirs()
        val proot = Proot(paths)
        val argv = proot.command(listOf("/bin/bash", "--login"), "/root", mapOf("COLORTERM" to "truecolor"))
        val env = proot.hostEnv().map { (k, v) -> "$k=$v" }
        return TerminalSession(argv.first(), paths.rootfs.absolutePath, argv.toTypedArray(), env.toTypedArray(), TRANSCRIPT_ROWS, client)
    }

    /**
     * TerminalSession.finishIfRunning() only SIGKILLs the proot process; guest processes are real
     * children of it and can outlive the tracer. Kill the whole tree (all our own uid, so allowed).
     */
    private fun kill(session: TerminalSession) {
        val pid = session.pid
        if (session.isRunning && pid > 0) {
            for (p in listOf(pid) + descendants(pid)) {
                try { Os.kill(p, OsConstants.SIGKILL) } catch (_: Exception) {}
            }
        }
        session.finishIfRunning()
    }

    private fun descendants(root: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            // ppid is the 2nd field after the ")" that ends the command name (which may contain spaces).
            val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull() ?: return@forEach
            children.getOrPut(ppid) { mutableListOf() } += pid
        }
        val out = mutableListOf<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) children[queue.removeFirst()]?.forEach { out += it; queue += it }
        return out
    }

    /** Patches Termux's global default palette once, before any emulator copies it. */
    private var colorsApplied = false
    private fun applyHudColors() {
        if (colorsApplied) return
        colorsApplied = true
        TerminalColors.COLOR_SCHEME.mDefaultColors.apply {
            this[TextStyle.COLOR_INDEX_FOREGROUND] = Hud.Text.toArgb()
            this[TextStyle.COLOR_INDEX_BACKGROUND] = Hud.Void.toArgb()
            this[TextStyle.COLOR_INDEX_CURSOR] = Hud.Cyan.toArgb()
        }
    }

    private fun shown(session: TerminalSession): TerminalView? = view?.takeIf { it.currentSession === session }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { shown(changedSession)?.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            _state.update { it.copy(revision = it.revision + 1) }
            shown(finishedSession)?.onScreenUpdated()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val cm = appContext?.getSystemService(ClipboardManager::class.java) ?: return
            if (!text.isNullOrEmpty()) cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val ctx = appContext ?: return
            val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) { shown(session)?.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

        override fun logError(tag: String?, message: String?) { Log.e(TAG, "$tag: $message") }
        override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$tag: $message") }
        override fun logInfo(tag: String?, message: String?) { Log.i(TAG, "$tag: $message") }
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(TAG, "$tag: $message", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, tag, e) }
    }
}
