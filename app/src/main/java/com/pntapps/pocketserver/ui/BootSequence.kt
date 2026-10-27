package com.pntapps.pocketserver.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pntapps.pocketserver.ui.components.ScrambleText
import com.pntapps.pocketserver.ui.motion.LocalMotion
import com.pntapps.pocketserver.ui.motion.animated
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The boot overlay plays once per app process; observable, so `if (!BootGate.shownThisProcess)` recomposes away. */
object BootGate {
    var shownThisProcess by mutableStateOf(false)
}

private const val BOOT_TITLE = "POCKETSERVER // CORE SYSTEMS"
private val BOOT_LINES = listOf(
    "PROOT KERNEL ........" to "ONLINE",
    "UBUNTU 24.04 ........" to "ONLINE",
    "LOCAL NETWORK ......." to "LINKED",
    "POWER POLICY ........" to "ARMED",
)
private const val BOOT_MS = 1_800f

/**
 * Full-screen startup overlay (~1.8 s): rings draw in, the server-rack emblem forms in a hexagon,
 * status lines decrypt one by one, then everything fades. Tap anywhere to skip. Marks [BootGate]
 * before calling [onDone]. With reduced motion it finishes immediately and draws nothing.
 */
@Composable
fun BootSequence(onDone: () -> Unit) {
    val latestDone by rememberUpdatedState(onDone)
    var finished by remember { mutableStateOf(false) }
    val finish = {
        if (!finished) { finished = true; BootGate.shownThisProcess = true; latestDone() }
    }
    if (!LocalMotion.current.animated) {
        LaunchedEffect(Unit) { finish() }
        return
    }

    val clock = remember { Animatable(0f) } // timeline in ms, read only while drawing
    val fade = remember { Animatable(1f) }
    var lines by remember { mutableIntStateOf(0) } // title + status lines revealed so far
    LaunchedEffect(Unit) {
        launch { clock.animateTo(BOOT_MS, tween(BOOT_MS.toInt(), easing = LinearEasing)) }
        delay(420)
        repeat(BOOT_LINES.size + 1) { lines++; delay(130) }
        delay(300)
        fade.animateTo(0f, tween(280))
        finish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Hud.Void)
            .pointerInput(Unit) { detectTapGestures { finish() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.size(220.dp).bootEmblem { clock.value })
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val title = HudType.Label.copy(color = Hud.Gold, fontSize = 13.sp)
                if (lines > 0) ScrambleText(BOOT_TITLE, title, durationMs = 420)
                else Text(BOOT_TITLE, Modifier.alpha(0f), style = title)
                Spacer(Modifier.height(4.dp))
                BOOT_LINES.forEachIndexed { i, (label, status) ->
                    val style = HudType.Readout.copy(fontSize = 13.sp, color = Hud.CyanDim)
                    Row {
                        if (lines > i + 1) {
                            ScrambleText(label, style, durationMs = 380)
                            Spacer(Modifier.width(8.dp))
                            ScrambleText(status, style.copy(color = Hud.Green), durationMs = 520)
                        } else Text("$label $status", Modifier.alpha(0f), style = style)
                    }
                }
            }
        }
    }
}

/** The emblem: three rings drawing in around a hexagon that assembles a three-unit server rack. */
private fun Modifier.bootEmblem(time: () -> Float) = drawWithCache {
    val c = size.center
    val r = size.minDimension / 2
    val dp = 1.dp.toPx()
    val glow = Brush.radialGradient(listOf(Hud.Cyan.copy(alpha = 0.28f), Color.Transparent), c, r * 0.7f)
    val hex = Array(7) { i -> val a = (PI / 3 * i - PI / 2).toFloat(); Offset(c.x + cos(a) * r * 0.42f, c.y + sin(a) * r * 0.42f) }
    fun ring(f: Float) = Offset(c.x - r * f, c.y - r * f) to Size(r * 2 * f, r * 2 * f)
    val (o1, s1) = ring(0.97f); val (o2, s2) = ring(0.84f); val (o3, s3) = ring(0.6f)
    onDrawBehind {
        val t = time()
        fun stage(start: Float, len: Float) = ((t - start) / len).coerceIn(0f, 1f)

        // Outer ring sweeps closed from the top.
        drawArc(Hud.Cyan.copy(alpha = 0.6f), -90f, 360f * stage(0f, 450f), false, o1, s1, style = Stroke(1.5f * dp))
        // Segmented ring: segments light one after another.
        val segs = (12 * stage(100f, 500f)).toInt()
        for (i in 0 until segs) drawArc(Hud.Cyan.copy(alpha = 0.45f), i * 30f - 90f + 3f, 24f, false, o2, s2, style = Stroke(r * 0.05f))
        // Tick ring.
        val ticks = (60 * stage(150f, 500f)).toInt()
        for (i in 0 until ticks) {
            val a = (2 * PI * i / 60 - PI / 2).toFloat()
            val len = if (i % 5 == 0) r * 0.06f else r * 0.03f
            drawLine(Hud.Cyan.copy(alpha = 0.5f), Offset(c.x + cos(a) * (r * 0.74f - len), c.y + sin(a) * (r * 0.74f - len)), Offset(c.x + cos(a) * r * 0.74f, c.y + sin(a) * r * 0.74f), dp)
        }
        // Gold bracket arcs spin in and settle.
        val g = stage(200f, 700f)
        rotate(-120f * (1f - g) * (1f - g)) {
            for (k in 0 until 3) drawArc(Hud.Gold.copy(alpha = 0.85f * g), k * 120f + 20f, 80f * g, false, o3, s3, style = Stroke(r * 0.035f))
        }
        // Core glow and hexagon outline drawn edge by edge.
        drawCircle(glow, r * 0.7f, c, alpha = stage(500f, 500f))
        val h = 6 * stage(250f, 500f)
        for (e in 0 until 6) {
            val f = (h - e).coerceIn(0f, 1f)
            if (f <= 0f) break
            val a = hex[e]; val b = hex[e + 1]
            drawLine(Hud.Cyan, a, Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f), 2 * dp, StrokeCap.Round)
        }
        // Rack units extend from the center, bottom first, each with a status light.
        for (u in 0 until 3) {
            val f = stage(550f + u * 90f, 260f)
            if (f <= 0f) continue
            val y = c.y + r * (0.13f - u * 0.13f)
            val half = r * 0.2f * f
            drawLine(Hud.Text.copy(alpha = 0.85f), Offset(c.x - half, y), Offset(c.x + half, y), r * 0.075f, StrokeCap.Butt)
            drawCircle((if (u == 2) Hud.Gold else Hud.Green).copy(alpha = f), r * 0.018f, Offset(c.x + half - r * 0.04f, y))
        }
        // A pulse leaves the emblem as the boot completes.
        val p = stage(1250f, 500f)
        if (p in 0.001f..0.999f) drawCircle(Hud.Cyan.copy(alpha = 0.5f * (1f - p)), r * (0.45f + 0.55f * p), c, style = Stroke(2 * dp))
    }
}
