package com.pntapps.pocketserver.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pntapps.pocketserver.backup.BackupFormat
import com.pntapps.pocketserver.backup.BackupKind
import com.pntapps.pocketserver.backup.BackupManager
import com.pntapps.pocketserver.backup.BackupState
import com.pntapps.pocketserver.catalog.Catalog
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.ui.components.HudButton
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.components.SegmentBar
import com.pntapps.pocketserver.ui.components.StatusChip
import com.pntapps.pocketserver.ui.components.chamferPath
import com.pntapps.pocketserver.ui.components.hudFrame
import com.pntapps.pocketserver.ui.components.hudSmallLabel
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import java.text.DateFormat
import java.util.Date

private const val MIN_PASSPHRASE = 8

/**
 * Passphrase of a backup whose file picker is open. Process-wide so it survives the activity being
 * recreated meanwhile; deliberately not saved instance state, which can end up on disk.
 */
private object PendingPassphrase {
    @Volatile private var value: CharArray? = null

    fun set(chars: CharArray) { clear(); value = chars }
    fun take(): CharArray? = value.also { value = null }
    fun clear() { value?.fill('\u0000'); value = null }
}

/** Backup and restore of app data. State lives in [BackupManager], so rotation is harmless. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupPanel(manager: AppManager, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backups = remember { BackupManager.get(context) }
    val state by backups.state.collectAsStateWithLifecycle()
    val sharedSize by backups.sharedSize.collectAsStateWithLifecycle()
    val apps by manager.apps.collectAsStateWithLifecycle()
    var includeShared by rememberSaveable { mutableStateOf(false) }
    var encrypt by rememberSaveable { mutableStateOf(true) }
    // Passphrases use plain remember: never written to saved instance state.
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var pickerError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { backups.refreshSharedSize() }

    val createPlain = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.mimeType(false))) { uri ->
        if (uri != null) backups.backup(uri, includeShared)
    }
    val createEncrypted = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.mimeType(true))) { uri ->
        val chars = PendingPassphrase.take()
        when {
            uri == null -> chars?.fill('\u0000')
            chars == null -> {
                // The app was restarted while the picker was open: never write an unencrypted .enc file.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                pickerError = "Enter the passphrase again, then create the backup."
            }
            else -> {
                backups.backup(uri, includeShared, chars)
                passphrase = ""; confirm = ""; reveal = false
            }
        }
    }
    // Any type: providers label .tar.gz inconsistently; the content itself is validated.
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backups.inspect(uri)
    }

    val installed = Catalog.apps.filter { apps[it.id]?.installed == true }
    val running = apps.values.count { it.active }
    val idle = state !is BackupState.Working && state !is BackupState.Inspected && state !is BackupState.NeedsPassphrase
    val passphraseProblem = when {
        !encrypt -> null
        passphrase.length < MIN_PASSPHRASE -> "Use at least $MIN_PASSPHRASE characters (a few random words work well)."
        confirm != passphrase -> "The passphrases do not match."
        else -> null
    }

    HudPanel(
        modifier,
        title = "Backup",
        accent = Hud.Cyan,
        trailing = { if (state is BackupState.Working) StatusChip("Working", Hud.Gold, pulse = true) },
    ) {
        Text(
            "▸ Uninstalling PocketServer deletes all app data, including Vaultwarden passwords. " +
                "Keep a backup somewhere else: a computer, SD card or cloud folder (encrypted only).",
            style = HudType.BodyDim,
        )
        Spacer(Modifier.height(10.dp))
        InfoRow("Apps", installed.joinToString { it.name }.ifEmpty { "None installed" }, prose = true)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include shared folder", style = HudType.Body)
                Text(
                    "/shared" + (sharedSize?.let { " · ${BackupFormat.formatBytes(it)}" } ?: ""),
                    style = HudType.Readout.copy(fontSize = 12.sp, color = Hud.TextDim),
                )
            }
            HudSwitch(includeShared, { includeShared = it }, enabled = idle)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Encrypt (recommended)", style = HudType.Body)
                Text("AES-256 with a passphrase", style = HudType.Readout.copy(fontSize = 12.sp, color = Hud.TextDim))
            }
            HudSwitch(encrypt, { encrypt = it; pickerError = null }, enabled = idle)
        }
        Spacer(Modifier.height(8.dp))
        if (encrypt) {
            PassphraseField(
                passphrase, { passphrase = it; pickerError = null }, "Passphrase", reveal, { reveal = !reveal },
                enabled = idle, imeAction = ImeAction.Next,
            )
            Spacer(Modifier.height(8.dp))
            PassphraseField(
                confirm, { confirm = it; pickerError = null }, "Confirm passphrase", reveal, { reveal = !reveal },
                enabled = idle,
            )
            Spacer(Modifier.height(6.dp))
            (pickerError ?: passphraseProblem?.takeIf { passphrase.isNotEmpty() || confirm.isNotEmpty() })?.let {
                Text(it, style = HudType.Body.copy(color = Hud.Red, fontSize = 13.sp))
                Spacer(Modifier.height(4.dp))
            }
            Text(
                "Backups contain your app passwords, API keys, certificate keys and the Vaultwarden vault. " +
                    "Without this passphrase the backup cannot be restored: there is no recovery. Keep it somewhere safe.",
                style = HudType.Body.copy(color = Hud.Gold, fontSize = 14.sp),
            )
        } else {
            Text(
                "Unencrypted: anyone who gets the file can read every password and key in it. " +
                    "Never store it in a cloud folder.",
                style = HudType.Body.copy(color = Hud.Red, fontSize = 14.sp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (running > 0) "$running running app${if (running == 1) "" else "s"} will be stopped during backup or restore."
            else "Running apps are stopped during backup or restore.",
            style = HudType.Body.copy(color = Hud.Gold, fontSize = 14.sp),
        )
        Spacer(Modifier.height(12.dp))
        // Flows: on narrow phones Restore moves to its own line instead of breaking mid-word.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudButton(
                "Create backup",
                onClick = {
                    pickerError = null
                    if (encrypt) {
                        PendingPassphrase.set(passphrase.toCharArray())
                        createEncrypted.launch(BackupFormat.defaultFileName(encrypted = true))
                    } else {
                        createPlain.launch(BackupFormat.defaultFileName())
                    }
                },
                enabled = idle && passphraseProblem == null,
                filled = true,
            )
            HudButton("Restore", onClick = { open.launch(arrayOf("*/*")) }, enabled = idle, accent = Hud.Gold)
        }

        when (val s = state) {
            BackupState.Idle -> Unit
            is BackupState.Working -> WorkingView(s, onCancel = backups::cancel)
            is BackupState.NeedsPassphrase -> UnlockBackup(s, onUnlock = { backups.inspect(s.uri, it) }, onCancel = backups::dismiss)
            is BackupState.Inspected -> ConfirmRestore(s, onConfirm = { backups.restore(s.uri) }, onCancel = backups::dismiss)
            is BackupState.Done -> ResultView(s.message, Hud.Green, backups::dismiss)
            is BackupState.Failed -> ResultView(
                (if (s.kind == BackupKind.Backup) "Backup failed: " else "Restore failed: ") + s.message,
                Hud.Red,
                backups::dismiss,
            )
        }
    }
}

@Composable
private fun HudSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Hud.Void, checkedTrackColor = Hud.Cyan, checkedBorderColor = Hud.Cyan,
            uncheckedThumbColor = Hud.TextDim, uncheckedTrackColor = Hud.Deep, uncheckedBorderColor = Hud.Line,
        ),
    )
}

/**
 * Label column + value. On phones (< 480 dp) the label column narrows, and [prose] values (long
 * lists) go under the label as body text instead of a narrow many-line column.
 */
@Composable
private fun InfoRow(label: String, value: String, prose: Boolean = false) {
    val narrow = LocalConfiguration.current.screenWidthDp < 480
    val labelStyle = HudType.Label.copy(color = Hud.TextDim, fontSize = hudSmallLabel)
    if (narrow && prose) {
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label.uppercase(), style = labelStyle)
            Spacer(Modifier.height(2.dp))
            Text(value, style = HudType.BodyDim)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label.uppercase(), style = labelStyle, modifier = Modifier.widthIn(min = if (narrow) 96.dp else 128.dp).padding(end = 10.dp))
        Text(value, style = HudType.Readout.copy(color = Hud.Text), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WorkingView(s: BackupState.Working, onCancel: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(s.step, style = HudType.Body)
    Spacer(Modifier.height(6.dp))
    val total = s.total
    if (total != null && total > 0) {
        SegmentBar(s.bytes.toFloat() / total, color = if (s.kind == BackupKind.Restore) Hud.Gold else Hud.Cyan)
        Spacer(Modifier.height(4.dp))
        Text(
            "${BackupFormat.formatBytes(s.bytes)} / ${BackupFormat.formatBytes(total)}",
            style = HudType.Readout.copy(fontSize = 12.sp),
        )
    } else if (s.bytes > 0) {
        Text(
            "${BackupFormat.formatBytes(s.bytes)} ${if (s.kind == BackupKind.Backup) "written" else "read"}",
            style = HudType.Readout.copy(fontSize = 12.sp),
        )
    }
    Spacer(Modifier.height(4.dp))
    Text("Keep PocketServer open until this finishes.", style = HudType.BodyDim)
    if (s.cancelable) {
        Spacer(Modifier.height(8.dp))
        HudButton("Cancel", onClick = onCancel, accent = Hud.Red)
    }
}

@Composable
private fun UnlockBackup(s: BackupState.NeedsPassphrase, onUnlock: (CharArray) -> Unit, onCancel: () -> Unit) {
    var passphrase by remember(s.uri) { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val submit = {
        if (passphrase.isNotEmpty()) {
            onUnlock(passphrase.toCharArray())
            passphrase = ""
        }
    }
    Spacer(Modifier.height(16.dp))
    HudPanel(title = "Encrypted backup", accent = Hud.Gold) {
        Text("Enter the passphrase that was set when this backup was created.", style = HudType.Body)
        Spacer(Modifier.height(10.dp))
        PassphraseField(
            passphrase, { passphrase = it }, "Passphrase", reveal, { reveal = !reveal },
            accent = Hud.Gold, onDone = submit,
        )
        if (s.wrongPassphrase) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Wrong passphrase. Check capitals and spaces, then try again.",
                style = HudType.Body.copy(color = Hud.Red, fontSize = 14.sp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudButton("Unlock", onClick = submit, enabled = passphrase.isNotEmpty(), accent = Hud.Gold, filled = true)
            HudButton("Cancel", onClick = onCancel)
        }
    }
}

@Composable
private fun ConfirmRestore(s: BackupState.Inspected, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val m = s.manifest
    val known = Catalog.apps.associateBy { it.id }
    Spacer(Modifier.height(16.dp))
    HudPanel(title = "Restore this backup?", accent = Hud.Gold) {
        InfoRow("Created", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.createdAt)))
        InfoRow("Made by", "PocketServer ${m.appVersion}")
        InfoRow("Apps", m.apps.joinToString { known[it]?.name ?: "$it (unknown, skipped)" }.ifEmpty { "None" }, prose = true)
        InfoRow("Shared", if (m.shared) "Included" else "Not included")
        InfoRow("Encryption", if (s.encrypted) "AES-256, unlocked" else "None")
        s.sizeBytes?.let { InfoRow("Size", BackupFormat.formatBytes(it)) }
        Spacer(Modifier.height(8.dp))
        Text(
            "Replaces the current data of these apps" +
                (if (m.shared) " and everything in the shared folder" else "") +
                ". Apps that are not installed are installed first (needs internet). " +
                "Your data is only replaced once the whole backup has been unpacked.",
            style = HudType.Body.copy(color = Hud.Gold, fontSize = 14.sp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudButton("Restore now", onClick = onConfirm, accent = Hud.Red, filled = true)
            HudButton("Cancel", onClick = onCancel)
        }
    }
}

@Composable
private fun ResultView(message: String, color: Color, onDismiss: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(message, style = HudType.Body.copy(color = color, fontSize = 15.sp))
    Spacer(Modifier.height(8.dp))
    HudButton("OK", onClick = onDismiss, accent = color)
}

/** Masked single-line input in a HUD frame, with a SHOW/HIDE toggle. */
@Composable
private fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    accent: Color = Hud.Cyan,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .hudFrame(if (enabled) accent.copy(alpha = if (focused) 1f else 0.7f) else Hud.Line, cut = 6.dp, fill = Hud.Deep)
            // Focused: a full-strength outline and glow over the frame's dim one.
            .drawBehind {
                if (!focused || !enabled) return@drawBehind
                val path = chamferPath(size, 6.dp.toPx())
                drawPath(path, accent.copy(alpha = 0.22f), style = Stroke(5.dp.toPx()))
                drawPath(path, accent, style = Stroke(1.5.dp.toPx()))
            }
            // A tap anywhere on the frame (not only on the text) focuses this field.
            .clickable(enabled = enabled, indication = null, interactionSource = remember { MutableInteractionSource() }) { focus.requestFocus() }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = HudType.Readout.copy(color = Hud.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(accent),
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp).focusRequester(focus).onFocusChanged {
                focused = it.isFocused
                // After the keyboard has opened (it shrinks the page), keep the field above it.
                if (it.isFocused) scope.launch { delay(350); bringIntoView.bringIntoView() }
            },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = HudType.Readout.copy(color = Hud.TextDim, fontSize = 15.sp))
                    inner()
                }
            },
        )
        Text(
            if (revealed) "HIDE" else "SHOW",
            style = HudType.Label.copy(color = if (enabled) accent else Hud.TextDim, fontSize = 10.sp),
            modifier = Modifier
                .clickable(enabled = enabled, role = Role.Button, onClick = onToggleReveal)
                .padding(horizontal = 10.dp, vertical = 12.dp),
        )
    }
}
