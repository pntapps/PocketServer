package com.pntapps.pocketserver.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pntapps.pocketserver.ui.components.HudButton
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.components.SegmentBar
import com.pntapps.pocketserver.ui.components.StatusChip
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import com.pntapps.pocketserver.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val latest: String) : UpdateUi
    data class Available(val release: Updater.Release) : UpdateUi
    data class Downloading(val release: Updater.Release, val progress: Float) : UpdateUi
    data class Ready(val release: Updater.Release, val apk: File) : UpdateUi
    data class Error(val message: String) : UpdateUi
}

/** Manual updater from GitHub Releases. Renders nothing when no update repo is configured. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdatePanel(modifier: Modifier = Modifier) {
    if (!Updater.enabled) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    val install by Updater.installStatus.collectAsState()

    fun check() {
        job = scope.launch {
            ui = UpdateUi.Checking
            ui = try {
                val r = Updater.checkLatest()
                if (r.newer) UpdateUi.Available(r) else UpdateUi.UpToDate(r.tag)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                UpdateUi.Error(e.message ?: "Update check failed")
            }
        }
    }

    fun install(release: Updater.Release, apk: File) {
        // Android 8+ asks once per installer app; send the user to that switch and let them tap again.
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            ui = UpdateUi.Ready(release, apk)
            return
        }
        Updater.resetInstallStatus()
        scope.launch {
            try { Updater.install(context, apk) } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // Reported through Updater.installStatus.
            }
        }
    }

    fun download(release: Updater.Release) {
        job = scope.launch {
            ui = UpdateUi.Downloading(release, 0f)
            try {
                val apk = Updater.download(context, release) { p -> ui = UpdateUi.Downloading(release, p) }
                ui = UpdateUi.Ready(release, apk)
                install(release, apk)
            } catch (e: CancellationException) {
                ui = UpdateUi.Available(release)
                throw e
            } catch (e: Exception) {
                ui = UpdateUi.Error(e.message ?: "Download failed")
            }
        }
    }

    HudPanel(modifier, title = "Updates") {
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("VERSION ${Updater.currentVersion}", style = HudType.Readout.copy(color = Hud.Text))
            Spacer(Modifier.width(10.dp))
            when (ui) {
                is UpdateUi.UpToDate -> StatusChip("Up to date", Hud.Green)
                is UpdateUi.Available, is UpdateUi.Ready -> StatusChip("Update available", Hud.Gold)
                is UpdateUi.Downloading -> StatusChip("Downloading", Hud.Gold, pulse = true)
                UpdateUi.Checking -> StatusChip("Checking", Hud.Cyan, pulse = true)
                is UpdateUi.Error -> StatusChip("Fault", Hud.Red)
                UpdateUi.Idle -> {}
            }
        }
        Text("Source: github.com/${Updater.repo} · checked only when you tap", style = HudType.BodyDim.copy(fontSize = 13.sp))

        when (val s = ui) {
            is UpdateUi.UpToDate -> {
                Spacer(Modifier.height(8.dp))
                Text("Latest release is ${s.latest}. You're on the newest build.", style = HudType.BodyDim)
            }
            is UpdateUi.Available -> ReleaseInfo(s.release)
            is UpdateUi.Ready -> ReleaseInfo(s.release)
            is UpdateUi.Downloading -> {
                ReleaseInfo(s.release)
                Spacer(Modifier.height(10.dp))
                SegmentBar(s.progress, color = Hud.Gold)
                Spacer(Modifier.height(4.dp))
                Text("${(s.progress * 100).toInt()}% of ${s.release.apkSize / 1_048_576} MB", style = HudType.Readout.copy(fontSize = 12.sp, color = Hud.TextDim))
            }
            is UpdateUi.Error -> {
                Spacer(Modifier.height(8.dp))
                Text(s.message, style = HudType.Body.copy(color = Hud.Red, fontSize = 14.sp), maxLines = 3)
            }
            else -> {}
        }

        when (val i = install) {
            Updater.InstallStatus.Installing -> {
                Spacer(Modifier.height(8.dp))
                Text("› Waiting for Android to install the update…", style = HudType.Readout.copy(color = Hud.Gold))
            }
            is Updater.InstallStatus.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text(i.message, style = HudType.Body.copy(color = Hud.Red, fontSize = 14.sp), maxLines = 4)
            }
            Updater.InstallStatus.Success -> {
                Spacer(Modifier.height(8.dp))
                Text("Update installed.", style = HudType.Body.copy(color = Hud.Green, fontSize = 14.sp))
            }
            Updater.InstallStatus.Idle -> {}
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Updating keeps your apps and data. Android only accepts updates signed with the same key as the installed app.",
            style = HudType.BodyDim.copy(fontSize = 13.sp),
        )

        Spacer(Modifier.height(14.dp))
        // Flows: "Download & install" + "Check again" are wider than a phone.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val s = ui) {
                UpdateUi.Checking -> HudButton("Checking…", {}, enabled = false)
                is UpdateUi.Available -> {
                    HudButton("Download & install", { download(s.release) }, accent = Hud.Gold, filled = true)
                    HudButton("Check again", ::check)
                }
                is UpdateUi.Downloading -> HudButton("Cancel", { job?.cancel() }, accent = Hud.Red)
                is UpdateUi.Ready -> {
                    HudButton("Install", { install(s.release, s.apk) }, accent = Hud.Gold, filled = true, enabled = install != Updater.InstallStatus.Installing)
                    HudButton("Check again", ::check)
                }
                else -> HudButton("Check for updates", ::check, accent = Hud.Cyan, filled = true)
            }
        }
    }
}

@Composable
private fun ReleaseInfo(release: Updater.Release) {
    Spacer(Modifier.height(8.dp))
    Text(release.name.uppercase(), style = HudType.Label.copy(color = Hud.Gold))
    if (release.notes.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(release.notes, style = HudType.BodyDim.copy(fontSize = 13.sp), maxLines = 8, overflow = TextOverflow.Ellipsis)
    }
}
