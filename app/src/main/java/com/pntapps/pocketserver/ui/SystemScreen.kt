package com.pntapps.pocketserver.ui

import com.pntapps.pocketserver.ui.components.hudReveal
import com.pntapps.pocketserver.ui.components.ScrambleText
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pntapps.pocketserver.BuildConfig
import com.pntapps.pocketserver.linux.RootfsInstaller
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.ui.components.HudButton
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.motion.MotionPrefs
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType

@Composable
fun SystemScreen(manager: AppManager) {
    val ips = remember { manager.lanAddresses() }
    val context = LocalContext.current
    val core = @Composable { m: Modifier ->
        HudPanel(m, title = "Core") {
            Readout("PocketServer", BuildConfig.VERSION_NAME)
            Readout("Linux", "Ubuntu ${RootfsInstaller.UBUNTU_VERSION} arm64 (proot, no root)")
            Readout("Network", ips.joinToString().ifEmpty { "Not on Wi-Fi" })
            Readout("Shared folder", if (manager.paths.storage.config.value.onSd.isEmpty()) "/shared" else "/shared (some folders on SD card)")
        }
    }
    // narrow (< 480 dp screens): the Full/Reduced buttons go under the description.
    val display = @Composable { m: Modifier, narrow: Boolean ->
        HudPanel(m, title = "Display") {
            val description = @Composable { cm: Modifier ->
                Column(cm) {
                    Text("Motion", style = HudType.Body)
                    Text(
                        if (MotionPrefs.systemAnimationsOff(context)) "Reduced, because Android's \"Remove animations\" is on."
                        else "Animations run only while PocketServer is on screen.",
                        style = HudType.BodyDim,
                    )
                }
            }
            val buttons = @Composable {
                HudButton("Full", { MotionPrefs.setReduced(context, false) }, filled = !MotionPrefs.reducedByUser)
                Spacer(Modifier.width(8.dp))
                HudButton("Reduced", { MotionPrefs.setReduced(context, true) }, filled = MotionPrefs.reducedByUser)
            }
            if (narrow) {
                description(Modifier)
                Spacer(Modifier.height(10.dp))
                Row { buttons() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    description(Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    buttons()
                }
            }
        }
    }
    val policy = @Composable { m: Modifier ->
        HudPanel(m, title = "Power & heat policy") {
            Text("▸ Nothing runs until you press Start. With everything stopped, PocketServer uses no battery.", style = HudType.BodyDim)
            Text("▸ While an app runs, a notification shows and the CPU and Wi-Fi stay awake so your devices can reach it.", style = HudType.BodyDim)
            Text("▸ Servers run on the cool efficiency cores at low priority. Transcoding is disabled.", style = HudType.BodyDim)
            Text("▸ When the device gets hot, everything stops and can't restart until it has cooled down.", style = HudType.BodyDim)
            Spacer(Modifier.height(8.dp))
            Text(
                "Using this as a 24/7 server on a charger? Turn on your device's own battery protection " +
                    "(Samsung: Settings › Battery › Protect battery) to keep the battery healthy.",
                style = HudType.Body.copy(color = Hud.Gold),
            )
        }
    }
    val privacy = @Composable { m: Modifier ->
        HudPanel(m, title = "Privacy") {
            Text("▸ Local network only. No remote access, no accounts, no telemetry.", style = HudType.BodyDim)
            Text("▸ Everything lives in this app's private storage. Uninstalling deletes all of it, including app data and passwords.", style = HudType.BodyDim)
            Spacer(Modifier.height(10.dp))
            Text("Open source under GPLv3. Ubuntu runs through proot (GPLv2).", style = HudType.BodyDim)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val narrow = maxWidth < 480.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 28.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ScrambleText("SYSTEM", HudType.Title)
            val full = Modifier.fillMaxWidth()
            if (wide) {
                // Two columns on tablets: settings you change on the left, data and information on the right.
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        core(full.hudReveal(0))
                        PowerSettingsPanel(full.hudReveal(2))
                        StoragePanel(manager, full.hudReveal(4))
                        display(full.hudReveal(6), narrow)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        BackupPanel(manager, full.hudReveal(1))
                        UpdatePanel(full)
                        policy(full.hudReveal(3))
                        privacy(full.hudReveal(5))
                    }
                }
            } else {
                core(full)
                PowerSettingsPanel(full)
                BackupPanel(manager, full)
                StoragePanel(manager, full)
                UpdatePanel(full)
                display(full, narrow)
                policy(full)
                privacy(full)
            }
        }
    }
}
