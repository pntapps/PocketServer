package com.pntapps.pocketserver.ui

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import com.pntapps.pocketserver.ui.components.hudReveal
import com.pntapps.pocketserver.ui.components.WireGlobe
import com.pntapps.pocketserver.ui.components.TelemetryGraph
import com.pntapps.pocketserver.ui.components.ScrambleText
import com.pntapps.pocketserver.ui.components.RadarSweep
import com.pntapps.pocketserver.ui.components.RadarBlip
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.aspectRatio
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.pntapps.pocketserver.catalog.Catalog
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.server.AppPhase
import com.pntapps.pocketserver.server.DeviceStats
import com.pntapps.pocketserver.server.Heat
import com.pntapps.pocketserver.server.StatsSnapshot
import com.pntapps.pocketserver.ui.components.ArcReactor
import com.pntapps.pocketserver.ui.components.HudButton
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.components.RingGauge
import com.pntapps.pocketserver.ui.components.SegmentBar
import com.pntapps.pocketserver.ui.components.StatusChip
import com.pntapps.pocketserver.ui.components.hudSmallLabel
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Samples device stats every 2 s, only while the screen is visible (stops when backgrounded). */
@Composable
fun rememberStats(context: Context): StatsSnapshot? {
    val stats = remember { DeviceStats(context) }
    var snapshot by remember { mutableStateOf<StatsSnapshot?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                snapshot = withContext(Dispatchers.IO) { stats.sample() }
                delay(2000)
            }
        }
    }
    return snapshot
}

@Composable
fun DashboardScreen(manager: AppManager, wide: Boolean, onOpenApps: () -> Unit) {
    val apps by manager.apps.collectAsState()
    val stats = rememberStats(LocalContext.current)
    val running = Catalog.apps.filter { apps[it.id]?.phase is AppPhase.Running }
    val installed = Catalog.services.count { apps[it.id]?.installed == true }
    val ip = remember(running.size) { manager.lanAddresses().firstOrNull() }
    val powerStatus by manager.powerStatus.collectAsState()
    val heat by manager.thermal.heat.collectAsState()
    // Short rolling history (2 s per sample) for the telemetry graphs; lives only while the screen does.
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val ramHistory = remember { mutableStateListOf<Float>() }
    LaunchedEffect(stats) {
        val s = stats ?: return@LaunchedEffect
        cpuHistory += s.serversCpuPercent / 100f
        ramHistory += 1f - s.ramAvailMb.toFloat() / s.ramTotalMb
        if (cpuHistory.size > 45) cpuHistory.removeAt(0)
        if (ramHistory.size > 45) ramHistory.removeAt(0)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 28.dp else 16.dp)) {
        val titleBlock = @Composable { m: Modifier ->
            Column(m) {
                // Same 22 sp as every other screen title; tighter tracking keeps it on one line on 360 dp.
                ScrambleText("POCKETSERVER", if (wide) HudType.Title else HudType.Title.copy(letterSpacing = 2.sp))
                ScrambleText(
                    if (ip != null) "LOCAL NODE · $ip" else "NO WI-FI · LOCAL NETWORK UNAVAILABLE",
                    HudType.Readout.copy(color = if (ip != null) Hud.Cyan else Hud.Gold),
                )
                powerStatus?.let { Text(it.uppercase(), style = HudType.Readout.copy(color = Hud.Gold)) }
            }
        }
        val statusChip = @Composable {
            if (heat >= Heat.Hot) StatusChip("Thermal lock · cooling", Hud.Red, pulse = true)
            else if (heat == Heat.Warm) StatusChip("Running warm · cooling mode", Hud.Gold)
            else if (running.isNotEmpty()) StatusChip("Systems online", Hud.Green, pulse = true)
            else StatusChip("Standby · 0% drain", Hud.TextDim)
        }
        // Narrow screens: the chip goes under the title, or it squeezes "POCKETSERVER" into two lines.
        if (wide) {
            Row(verticalAlignment = Alignment.Bottom) {
                titleBlock(Modifier.weight(1f))
                statusChip()
            }
        } else {
            titleBlock(Modifier)
            Spacer(Modifier.height(8.dp))
            statusChip()
        }
        Spacer(Modifier.height(20.dp))

        val reactor = @Composable { m: Modifier ->
            HudPanel(m.hudReveal(0), title = "Reactor core") {
                // Wide: fills the row height computed below. Narrow: a bounded square, so the reactor
                // can never draw outside its panel (it did over Telemetry in portrait).
                val area = if (wide) Modifier.fillMaxWidth().weight(1f, fill = false)
                else Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f).align(Alignment.CenterHorizontally)
                Box(area.clipToBounds(), contentAlignment = Alignment.Center) {
                    ArcReactor(running.size, installed, Modifier.fillMaxWidth().padding(8.dp))
                }
            }
        }
        val gauges = @Composable { m: Modifier -> TelemetryPanel(stats, m.hudReveal(1)) }
        val traces = @Composable { m: Modifier ->
            HudPanel(m.hudReveal(2), title = "Live traces") {
                val graph = if (wide) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(96.dp)
                TelemetryGraph(cpuHistory, graph, color = Hud.Gold, label = "SERVER CPU")
                Spacer(Modifier.height(12.dp))
                TelemetryGraph(ramHistory, graph, color = Hud.Cyan, label = "DEVICE MEMORY")
            }
        }
        val blips = Catalog.services.mapIndexedNotNull { i, app ->
            val state = apps[app.id]?.takeIf { it.installed } ?: return@mapIndexedNotNull null
            val color = when (state.phase) {
                is AppPhase.Running -> Hud.Green
                is AppPhase.Failed -> Hud.Red
                AppPhase.Starting, AppPhase.Stopping -> Hud.Gold
                else -> Hud.CyanDim
            }
            // Only apps that are up (or starting/failed) are labelled: with many installed, names of
            // idle apps would pile on top of each other.
            val label = if (state.phase == AppPhase.Stopped) "" else app.name.uppercase()
            RadarBlip(angleDeg = 40f + i * 360f / Catalog.services.size, distance = 0.45f + (i % 3) * 0.2f, color = color, label = label)
        }
        // Radar and globe share a row on phones: short titles keep both on one line, and matching
        // one-line captions make the two panels end at the same height.
        val caption = if (wide) HudType.Readout else HudType.Readout.copy(fontSize = 12.sp)
        val radar = @Composable { m: Modifier ->
            HudPanel(m.hudReveal(3), title = if (wide) "Service sweep" else "Sweep") {
                RadarSweep(blips, Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp))
                Text(
                    "$installed INSTALLED", style = caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
        val globe = @Composable { m: Modifier ->
            HudPanel(m.hudReveal(4), title = if (wide) "Local uplink" else "Uplink") {
                WireGlobe(Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp), active = running.isNotEmpty())
                Text(
                    ip ?: "OFFLINE", style = caption.copy(color = if (ip != null) Hud.Cyan else Hud.Gold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
        val active = @Composable { m: Modifier ->
            HudPanel(m.hudReveal(5), title = "Active systems") {
                if (running.isEmpty()) {
                    Text("Nothing is running, so PocketServer uses no battery right now.", style = HudType.BodyDim)
                    Spacer(Modifier.height(12.dp))
                    HudButton(if (installed == 0) "Install your first app" else "Start an app", onOpenApps, accent = Hud.Gold)
                } else running.forEachIndexed { i, app ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    // The scheme is implied for plain HTTP; https:// stays visible.
                    val url = app.url(ip ?: "127.0.0.1").removePrefix("http://")
                    if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.name.uppercase(), style = HudType.Label.copy(color = Hud.Text))
                                Text(url, style = HudType.Readout)
                            }
                            HudButton("Stop", { manager.stop(app.id) }, accent = Hud.Red)
                        }
                    } else {
                        // Phones: Stop beside the name and the address on its own full-width line.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.name.uppercase(), style = HudType.Label.copy(color = Hud.Text), modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            HudButton("Stop", { manager.stop(app.id) }, accent = Hud.Red)
                        }
                        Text(url, style = HudType.Readout)
                    }
                }
            }
        }

        if (wide) BoxWithConstraints {
            // Each row gets one explicit height derived from its square visual (reactor, radar), so
            // no column ends early and leaves a hole. Intrinsic sizing can't be used: some effects
            // are subcomposed.
            val gap = 20.dp
            val reactorHeight = (maxWidth - gap) * (0.9f / 2.3f) + 40.dp
            val sensorHeight = (maxWidth - gap * 2) * (1f / 3.3f) + 60.dp
            Column {
                Row(Modifier.height(reactorHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    reactor(Modifier.weight(0.9f).fillMaxHeight())
                    Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        gauges(Modifier.fillMaxWidth())
                        traces(Modifier.fillMaxWidth().weight(1f))
                    }
                }
                Spacer(Modifier.height(gap))
                Row(Modifier.height(sensorHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    radar(Modifier.weight(1f).fillMaxHeight())
                    globe(Modifier.weight(1f).fillMaxHeight())
                    active(Modifier.weight(1.3f).fillMaxHeight())
                }
            }
        } else {
            reactor(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            gauges(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            active(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                radar(Modifier.weight(1f))
                globe(Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            traces(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TelemetryPanel(stats: StatsSnapshot?, modifier: Modifier) {
    HudPanel(modifier, title = "Telemetry") {
        if (stats == null) { Text("Calibrating…", style = HudType.Readout); return@HudPanel }
        val ramUsed = 1f - stats.ramAvailMb.toFloat() / stats.ramTotalMb
        val batteryColor = when {
            stats.charging -> Hud.Green
            stats.batteryPercent <= 15 -> Hud.Red
            else -> Hud.Cyan
        }
        val tempColor = if (stats.batteryTempC >= 40f) Hud.Red else if (stats.batteryTempC >= 36f) Hud.Gold else Hud.Cyan
        val gauges = listOf<@Composable () -> Unit>(
            { RingGauge(stats.batteryPercent / 100f, "${stats.batteryPercent}%", if (stats.charging) "Charging" else "Battery", color = batteryColor) },
            { RingGauge(ramUsed, "${(ramUsed * 100).toInt()}%", "Memory") },
            { RingGauge(stats.serversCpuPercent / 100f, "%.0f%%".format(stats.serversCpuPercent), "Server CPU", color = Hud.Gold) },
            { RingGauge((stats.batteryTempC - 20f) / 30f, "%.0f °C".format(stats.batteryTempC), "Temp", color = tempColor) },
        )
        // Four 104 dp gauges need ~480 dp; narrower panels (phones, portrait) get two rows of two.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val rows = if (maxWidth < 480.dp) gauges.chunked(2) else listOf(gauges)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { row.forEach { it() } }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Readout("Servers memory", "${stats.serversRamMb} MB")
        Readout("Device memory free", "${stats.ramAvailMb} / ${stats.ramTotalMb} MB")
        Readout("Storage free", "%.1f / %.1f GB".format(stats.storageFreeGb, stats.storageTotalGb))
        Spacer(Modifier.height(6.dp))
        SegmentBar(1f - stats.storageFreeGb / stats.storageTotalGb, color = Hud.CyanDim)
    }
}

/**
 * Label left, value right. If both don't fit on one line the label keeps at least 96 dp and wraps;
 * if the value alone leaves less than that, the value goes on its own line under the label.
 * Measure-based (no subcomposition), so it is safe anywhere.
 */
@Composable
fun Readout(label: String, value: String) {
    Layout(
        content = {
            Text(label.uppercase(), style = HudType.Label.copy(color = Hud.TextDim, fontSize = hudSmallLabel))
            Text(value, style = HudType.Readout, textAlign = TextAlign.End)
            Text(value, style = HudType.Readout)
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) { measurables, constraints ->
        val (labelM, sideValueM, stackedValueM) = measurables
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val valueW = sideValueM.maxIntrinsicWidth(Constraints.Infinity)
        val labelSpace = width - gap - valueW
        if (!constraints.hasBoundedWidth || labelSpace >= 96.dp.roundToPx()) {
            val v = sideValueM.measure(Constraints(maxWidth = if (constraints.hasBoundedWidth) valueW else Constraints.Infinity))
            val l = labelM.measure(Constraints(maxWidth = if (constraints.hasBoundedWidth) labelSpace else Constraints.Infinity))
            val w = if (constraints.hasBoundedWidth) width else l.width + gap + v.width
            val h = maxOf(l.height, v.height)
            layout(w, h) {
                l.place(0, (h - l.height) / 2)
                v.place(w - v.width, (h - v.height) / 2)
            }
        } else {
            val l = labelM.measure(Constraints(maxWidth = width))
            val v = stackedValueM.measure(Constraints(maxWidth = width))
            val between = 2.dp.roundToPx()
            layout(width, l.height + between + v.height) {
                l.place(0, 0)
                v.place(0, l.height + between)
            }
        }
    }
}
