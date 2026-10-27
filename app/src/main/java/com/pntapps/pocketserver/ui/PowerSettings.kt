package com.pntapps.pocketserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.server.PowerMode
import com.pntapps.pocketserver.server.PowerRules
import com.pntapps.pocketserver.server.PowerSettings
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.components.StatusChip
import com.pntapps.pocketserver.ui.components.chamferPath
import com.pntapps.pocketserver.ui.components.chamferShape
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import kotlinx.coroutines.launch

/** Every power setting with a one-line explanation. Changes apply at once, even to running apps. */
@Composable
fun PowerSettingsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val power = remember { AppManager.get(context).power }
    val settings by power.store.settings.collectAsState(initial = PowerSettings())
    val status by power.status.collectAsState()
    val scope = rememberCoroutineScope()
    fun update(transform: (PowerSettings) -> PowerSettings) { scope.launch { power.store.update(transform) } }

    HudPanel(modifier, title = "Power settings") {
        status?.let {
            StatusChip(it, Hud.Gold)
            Spacer(Modifier.height(12.dp))
        }
        Section("Mode", "On-demand stops apps by themselves. Home server keeps them up until you stop them.") { cell ->
            Choice("On-demand", settings.mode == PowerMode.ON_DEMAND, cell) { update { it.copy(mode = PowerMode.ON_DEMAND) } }
            Choice("Home server", settings.mode == PowerMode.HOME_SERVER, cell) { update { it.copy(mode = PowerMode.HOME_SERVER) } }
        }
        if (settings.mode == PowerMode.ON_DEMAND) {
            Section("Run time", "Each app stops this long after it starts.") { cell ->
                PowerSettings.ON_DEMAND_CHOICES.forEach { m ->
                    Choice(PowerRules.duration(m), settings.onDemandMinutes == m, cell) { update { it.copy(onDemandMinutes = m) } }
                }
            }
            Section("Stop when idle", "Stops everything early if no device has used it for this long.") { cell ->
                PowerSettings.IDLE_CHOICES.forEach { m ->
                    Choice(if (m == 0) "Off" else PowerRules.duration(m), settings.idleStopMinutes == m, cell) {
                        update { it.copy(idleStopMinutes = m) }
                    }
                }
            }
        } else {
            Section(
                "Only while charging",
                "Unplugging pauses running apps with no battery use; plugging in starts them again.",
            ) { cell ->
                Choice("On", settings.homeOnlyWhileCharging, cell) { update { it.copy(homeOnlyWhileCharging = true) } }
                Choice("Off", !settings.homeOnlyWhileCharging, cell) { update { it.copy(homeOnlyWhileCharging = false) } }
            }
        }
        Section("Low battery stop", "Stops everything at this charge unless the phone is plugged in.") { cell ->
            PowerSettings.LOW_BATTERY_CHOICES.forEach { p ->
                Choice("$p%", settings.lowBatteryPercent == p, cell) { update { it.copy(lowBatteryPercent = p) } }
            }
        }
        Section("Heat limit", "Stops everything if the battery gets this hot, charging or not.") { cell ->
            PowerSettings.HEAT_CHOICES.forEach { c ->
                Choice("$c °C", settings.heatLimitC == c, cell) { update { it.copy(heatLimitC = c) } }
            }
        }
        Text(
            "▸ Jellyfin library scans run only while charging, at most once a day.",
            style = HudType.BodyDim, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Phones and small tablets: every option row is one row of equal-width cells (no orphan wrapping). */
@Composable
private fun compactChoices() = LocalConfiguration.current.screenWidthDp < 840

/** [choices] gets the modifier for each [Choice]: an equal-weight cell on phones, nothing on tablets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, explanation: String, choices: @Composable (Modifier) -> Unit) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title.uppercase(), style = HudType.Label.copy(color = Hud.Text, fontSize = 11.sp))
        Spacer(Modifier.height(2.dp))
        Text(explanation, style = HudType.BodyDim)
        Spacer(Modifier.height(8.dp))
        if (compactChoices()) {
            // Intrinsic height is safe here (plain Choice cells, no HUD effects): a cell whose label
            // wraps to two lines makes the whole row that tall.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices(Modifier)
            }
        }
    }
}

/**
 * Compact chamfered option: a smaller HudButton so five choices fit on a phone. Compact cells are
 * at least 48 dp tall (touch target) with tighter padding and tracking, label centered.
 */
@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (selected) Hud.Cyan else Hud.TextDim
    val compact = compactChoices()
    Box(
        modifier
            .then(if (compact) Modifier.heightIn(min = 48.dp) else Modifier)
            .clip(chamferShape(4.dp))
            .background(color.copy(alpha = if (selected) 0.25f else 0.06f))
            .drawBehind { drawPath(chamferPath(size, 4.dp.toPx() * 2.75f), color.copy(alpha = 0.8f), style = Stroke(1.dp.toPx())) }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = if (compact) 4.dp else 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = HudType.Label.copy(
                color = if (selected) Hud.Text else color, fontSize = 11.sp,
                letterSpacing = if (compact) 1.sp else HudType.Label.letterSpacing,
            ),
            textAlign = TextAlign.Center,
        )
    }
}
