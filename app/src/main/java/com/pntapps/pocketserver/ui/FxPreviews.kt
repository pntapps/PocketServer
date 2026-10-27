package com.pntapps.pocketserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pntapps.pocketserver.ui.components.HoloRing
import com.pntapps.pocketserver.ui.components.HudPanel
import com.pntapps.pocketserver.ui.components.RadarBlip
import com.pntapps.pocketserver.ui.components.RadarSweep
import com.pntapps.pocketserver.ui.components.ScrambleText
import com.pntapps.pocketserver.ui.components.TelemetryGraph
import com.pntapps.pocketserver.ui.components.WireGlobe
import com.pntapps.pocketserver.ui.components.hudLiveBackground
import com.pntapps.pocketserver.ui.components.hudReveal
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import com.pntapps.pocketserver.ui.theme.PocketServerTheme
import kotlin.math.sin

// Preview-only: inspect each motion effect in Android Studio (use interactive mode to see motion).

@Composable
private fun Stage(content: @Composable () -> Unit) = PocketServerTheme {
    Box(Modifier.background(Hud.Void).padding(16.dp), contentAlignment = Alignment.Center) { content() }
}

@Preview(widthDp = 400, heightDp = 300)
@Composable
private fun LiveBackgroundPreview() = PocketServerTheme {
    Box(Modifier.fillMaxSize().hudLiveBackground().padding(16.dp)) {
        HudPanel(Modifier.fillMaxWidth().hudReveal(0), title = "Revealed panel") { ScrambleText("DECRYPTING TELEMETRY", HudType.Readout) }
    }
}

@Preview
@Composable
private fun RadarPreview() = Stage {
    RadarSweep(
        listOf(
            RadarBlip(30f, 0.6f, Hud.Cyan, "nextcloud"),
            RadarBlip(140f, 0.35f, Hud.Gold, "jellyfin"),
            RadarBlip(260f, 0.8f, Hud.Green, "phone"),
        ),
        Modifier.size(260.dp),
    )
}

@Preview
@Composable
private fun GlobePreview() = Stage {
    Column {
        WireGlobe(Modifier.size(220.dp), active = true)
        WireGlobe(Modifier.size(120.dp), active = false)
    }
}

@Preview
@Composable
private fun GraphAndRingPreview() = Stage {
    Column {
        TelemetryGraph(List(30) { 0.5f + 0.35f * sin(it / 3f) }, Modifier.size(300.dp, 110.dp), label = "Server CPU")
        TelemetryGraph(List(30) { 0.3f + 0.1f * sin(it / 2f) }, Modifier.fillMaxWidth().height(80.dp), Hud.Gold)
        HoloRing(Modifier.size(180.dp))
    }
}

@Preview(widthDp = 400, heightDp = 640)
@Composable
private fun BootPreview() = PocketServerTheme { BootSequence {} }
