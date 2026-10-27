package com.pntapps.pocketserver.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

/** CTRL/ALT from the extra-keys row: armed by a tap, consumed by the next key sent to the shell. */
class StickyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun consumeCtrl() = ctrl.also { ctrl = false }
    fun consumeAlt() = alt.also { alt = false }
}

/** Glue between TerminalView and the app: keyboard on tap, pinch-to-zoom, sticky keys, restart on Enter. */
class HudTerminalViewClient(
    private val modifiers: StickyModifiers,
    private val showKeyboard: () -> Unit,
    private val restart: (TerminalSession) -> Unit,
) : TerminalViewClient {

    /** TerminalView reports the cumulative pinch scale; step the font once it moves 10% and reset. */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            TerminalSessions.changeFont(if (scale > 1f) 1 else -1)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()

    override fun shouldBackButtonBeMappedToEscape() = false
    // Plain char input: stops predictive keyboards from buffering words before sending them.
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            restart(session)
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = modifiers.consumeCtrl()
    override fun readAltKey() = modifiers.consumeAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if ((codePoint == '\n'.code || codePoint == '\r'.code) && !session.isRunning) {
            restart(session)
            return true
        }
        return false
    }

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { Log.e("PocketTerminal", "$tag: $message") }
    override fun logWarn(tag: String?, message: String?) { Log.w("PocketTerminal", "$tag: $message") }
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e("PocketTerminal", "$tag: $message", e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e("PocketTerminal", tag, e) }
}
