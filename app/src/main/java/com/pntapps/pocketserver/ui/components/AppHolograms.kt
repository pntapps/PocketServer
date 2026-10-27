package com.pntapps.pocketserver.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pntapps.pocketserver.ui.motion.LocalMotion
import com.pntapps.pocketserver.ui.motion.Motion
import com.pntapps.pocketserver.ui.motion.animated
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** What the app is doing, as far as its hologram cares. */
enum class HologramState { Offline, Busy, Online, Fault }

/*
 * Every hologram is drawn in a 100x100 unit box and scaled to fit, so paths and strokes are built
 * once and never per frame. All motion derives from one 0..1 phase looping every LOOP_MS; speeds are
 * whole cycles per loop so the wrap-around is seamless.
 */
private const val U = 100f
private const val LOOP_MS = 12_000
private const val STILL_PHASE = 0.11f
private const val GLOW_ALPHA = 0.22f
private val ThinStroke = Stroke(1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val GlowStroke = Stroke(4.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val HoloGreen = lerp(Hud.Green, Hud.Cyan, 0.45f)
private val HoloDim = lerp(Hud.CyanDim, Hud.Cyan, 0.3f)

/** Per-state styling: [spin] rotations and [flow] packet cycles per loop. */
private class Look(val color: Color, val spin: Int, val flow: Int, val bob: Boolean)

private fun lookOf(state: HologramState) = when (state) {
    HologramState.Offline -> Look(HoloDim, spin = 0, flow = 0, bob = false)
    HologramState.Busy -> Look(Hud.Gold, spin = 4, flow = 12, bob = true)
    HologramState.Online -> Look(HoloGreen, spin = 1, flow = 4, bob = true)
    HologramState.Fault -> Look(Hud.Red, spin = 0, flow = 0, bob = false)
}

/**
 * Holographic illustration of a catalog app floating over a projector base. Draws a single still
 * frame under reduced motion; otherwise only the draw phase reruns per frame (no recomposition).
 */
@Composable
fun AppHologram(appId: String, state: HologramState, modifier: Modifier = Modifier) {
    val animated = LocalMotion.current.animated
    val phase: State<Float> = if (animated) {
        rememberInfiniteTransition(label = "hologram")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)), label = "phase")
    } else {
        remember { mutableFloatStateOf(STILL_PHASE) }
    }
    Spacer(
        modifier.size(84.dp).drawWithCache {
            val look = lookOf(state)
            val figure = figureFor(appId)
            val base = ProjectorBase(look.color)
            val band = Brush.verticalGradient(listOf(Color.Transparent, look.color.copy(alpha = 0.35f), Color.Transparent), 0f, 12f)
            onDrawBehind {
                val s = size.minDimension / U
                withTransform({
                    translate((size.width - U * s) / 2f, (size.height - U * s) / 2f)
                    scale(s, s, Offset.Zero)
                }) {
                    drawHologram(figure, base, band, look, state, phase.value, animated)
                }
            }
        },
    )
}

private fun DrawScope.drawHologram(fig: Figure, base: ProjectorBase, band: Brush, look: Look, state: HologramState, p: Float, animated: Boolean) {
    val c = look.color
    var alpha = 1f
    var dx = 0f
    var glitch = false
    var seg = 0
    when (state) {
        HologramState.Offline -> {
            alpha = 0.72f
            if (animated) {
                // Slow breathing plus the odd brownout, like a projector short of power.
                val n = noise((p * 120f).toInt())
                alpha = 0.66f + 0.1f * sin(2f * PI.toFloat() * 2f * p) - if (n > 0.94f) 0.4f else if (n > 0.88f) 0.2f else 0f
            }
        }
        HologramState.Fault -> if (animated) {
            seg = (p * 80f).toInt()
            if (noise(seg + 7) > 0.68f) {
                glitch = true
                dx = (noise(seg * 3 + 1) - 0.5f) * 9f
            }
            alpha = if (noise(seg + 99) > 0.85f) 0.55f else 1f
        } else {
            glitch = true; dx = 1.6f
        }
        else -> {}
    }
    val bob = if (look.bob && animated) sin(2f * PI.toFloat() * 3f * p) * 1.8f else 0f
    val spin = 360f * look.spin * p + 20f
    val flow = look.flow * p

    with(base) { draw(alpha, flow, look.flow > 0) }
    if (glitch) {
        // Chromatic ghost displaced the other way.
        translate(-dx * 0.8f - 1.2f, bob) { with(fig) { draw(Hud.Cyan, alpha * 0.28f, spin, flow) } }
    }
    translate(dx, bob) { with(fig) { draw(c, alpha, spin, flow) } }
    if (glitch) {
        val y1 = if (animated) 20f + 50f * noise(seg + 3) else 47f
        drawLine(c, Offset(16f, y1), Offset(84f, y1), 0.8f, alpha = 0.7f)
        if (animated) {
            val y2 = 20f + 50f * noise(seg + 5)
            drawLine(c, Offset(24f + dx, y2), Offset(70f + dx, y2), 2.2f, alpha = 0.35f)
        }
    }
    if (state == HologramState.Busy) {
        // Scanning band sweeping top to bottom.
        val y = 14f + 62f * frac(p * 6f)
        translate(0f, y - 6f) {
            drawRect(band, Offset(14f, 0f), Size(72f, 12f))
            drawLine(c, Offset(16f, 6f), Offset(84f, 6f), 0.7f, alpha = 0.9f)
        }
    }
}

// ---------- Drawing primitives: every stroke is a wide faint glow under a thin bright line. ----------

private fun DrawScope.glowPath(path: Path, c: Color, a: Float) {
    drawPath(path, c, alpha = (a * GLOW_ALPHA).coerceIn(0f, 1f), style = GlowStroke)
    drawPath(path, c, alpha = a.coerceIn(0f, 1f), style = ThinStroke)
}

private fun DrawScope.glowLine(c: Color, a: Float, from: Offset, to: Offset) {
    drawLine(c, from, to, GlowStroke.width, StrokeCap.Round, alpha = (a * GLOW_ALPHA).coerceIn(0f, 1f))
    drawLine(c, from, to, ThinStroke.width, StrokeCap.Round, alpha = a.coerceIn(0f, 1f))
}

private fun DrawScope.glowCircle(c: Color, a: Float, center: Offset, r: Float) {
    drawCircle(c, r, center, (a * GLOW_ALPHA).coerceIn(0f, 1f), GlowStroke)
    drawCircle(c, r, center, a.coerceIn(0f, 1f), ThinStroke)
}

private fun DrawScope.glowArc(c: Color, a: Float, start: Float, sweep: Float, center: Offset, r: Float) {
    val tl = Offset(center.x - r, center.y - r)
    val sz = Size(r * 2f, r * 2f)
    drawArc(c, start, sweep, false, tl, sz, (a * GLOW_ALPHA).coerceIn(0f, 1f), GlowStroke)
    drawArc(c, start, sweep, false, tl, sz, a.coerceIn(0f, 1f), ThinStroke)
}

/** Soft-haloed point of light (packets, LEDs, bolts). */
private fun DrawScope.spark(c: Color, a: Float, center: Offset, r: Float) {
    drawCircle(c, r * 2.6f, center, (a * 0.25f).coerceIn(0f, 1f))
    drawCircle(c, r, center, a.coerceIn(0f, 1f))
}

private fun frac(x: Float) = x - floor(x)

/** 0 at the ends of a cycle, 1 in the middle: fades travelling things in and out. */
private fun fade(f: Float) = sin(PI.toFloat() * f)

/** Cheap deterministic hash in 0..1, used for flicker and glitch timing. */
private fun noise(i: Int): Float {
    var x = i * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
}

private fun hexagon(cx: Float, cy: Float, rx: Float, ry: Float, startDeg: Float = 0f) = Path().apply {
    for (i in 0..5) {
        val a = Math.toRadians((startDeg + 60f * i).toDouble())
        val x = cx + rx * cos(a).toFloat()
        val y = cy + ry * sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

// ---------- Projector base ----------

/** Flattened hex emitter at the bottom with a light cone rising into the figure. */
private class ProjectorBase(val c: Color) {
    val hex = hexagon(50f, 88f, 24f, 6.5f)
    val cone = Path().apply { moveTo(30f, 87f); lineTo(14f, 20f); lineTo(86f, 20f); lineTo(70f, 87f); close() }
    val coneBrush = Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = 0.07f), c.copy(alpha = 0.2f)), 20f, 88f)

    fun DrawScope.draw(a: Float, flow: Float, emitting: Boolean) {
        drawPath(cone, coneBrush, alpha = a.coerceIn(0f, 1f))
        drawPath(hex, c, alpha = (a * 0.1f).coerceIn(0f, 1f))
        glowPath(hex, c, a * 0.8f)
        drawOval(c, Offset(40f, 85.5f), Size(20f, 5f), alpha = (a * 0.5f).coerceIn(0f, 1f))
        if (emitting) {
            // Rings lifting off the emitter.
            for (i in 0..1) {
                val f = frac(flow * 0.5f + i * 0.5f)
                val rx = 8f + 18f * f
                val ry = 2f + 4.5f * f
                drawOval(c, Offset(50f - rx, 88f - ry - 14f * f), Size(rx * 2f, ry * 2f), alpha = (a * (1f - f) * 0.5f).coerceIn(0f, 1f), style = ThinStroke)
            }
        }
    }
}

// ---------- Figures ----------

private abstract class Figure {
    /** [spin] is degrees, [flow] is a continuous cycle count (fractional part = position). */
    abstract fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float)
}

private fun figureFor(appId: String): Figure = when (appId) {
    "filebrowser", "nextcloud", "calibreweb", "memos" -> FileStack()
    "syncthing", "qbittorrent", "prowlarr" -> SyncNodes()
    "jellyfin", "navidrome", "audiobookshelf", "sonarr", "radarr" -> MediaScreens()
    "vaultwarden" -> VaultShield()
    "bentopdf" -> PdfToolkit()
    "devtools" -> TerminalWindow()
    "codestudio", "pyweb", "react", "gitea" -> CodeWindow()
    "phpsites", "laravel", "wordpress" -> BrowserWindow()
    "mariadb", "mongodb" -> DatabaseStack()
    else -> ServerRack()
}

/** Folders stacked in depth; documents rise out of the front one and fade away. */
private class FileStack : Figure() {
    val back = Path().apply { moveTo(28f, 30f); lineTo(40f, 30f); lineTo(44f, 34f); lineTo(70f, 34f); lineTo(70f, 62f); lineTo(28f, 62f); close() }
    val flap = Path().apply { moveTo(32f, 42f); lineTo(76f, 42f); lineTo(70f, 62f); lineTo(28f, 62f); close() }
    val doc = Path().apply {
        moveTo(0f, 0f); lineTo(13f, 0f); lineTo(17f, 4f); lineTo(17f, 20f); lineTo(0f, 20f); close()
        moveTo(13f, 0f); lineTo(13f, 4f); lineTo(17f, 4f)
    }

    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        translate(10f, -9f) { glowPath(back, c, a * 0.22f); glowPath(flap, c, a * 0.22f) }
        translate(5f, -4.5f) { glowPath(back, c, a * 0.45f); glowPath(flap, c, a * 0.45f) }
        glowPath(back, c, a)
        for (i in 0..1) {
            val f = frac(flow * 0.5f + i * 0.5f)
            val da = a * fade(f)
            translate(36f + i * 9f + f * 4f, 44f - f * 24f) {
                drawPath(doc, c, alpha = (da * 0.1f).coerceIn(0f, 1f))
                glowPath(doc, c, da)
                for (l in 0..2) drawLine(c, Offset(3f, 8f + l * 3.5f), Offset(14f - l * 2f, 8f + l * 3.5f), 0.8f, alpha = (da * 0.7f).coerceIn(0f, 1f))
            }
        }
        // The front flap is filled with void so it hides the bottom of the documents.
        drawPath(flap, Hud.Void, alpha = 0.85f)
        drawPath(flap, c, alpha = (a * 0.12f).coerceIn(0f, 1f))
        glowPath(flap, c, a)
        drawLine(c, Offset(36f, 56f), Offset(48f, 56f), 0.8f, alpha = (a * 0.6f).coerceIn(0f, 1f))
    }
}

/** Two device nodes, a rotating sync symbol between them and packets arcing both ways. */
private class SyncNodes : Figure() {
    val node = hexagon(0f, 0f, 9f, 9f, 30f)
    val arrows = Path().apply {
        val r = 10f
        for (start in floatArrayOf(200f, 20f)) {
            val sweep = 130f
            addArc(Rect(-r, -r, r, r), start, sweep)
            val e = Math.toRadians((start + sweep).toDouble())
            val ex = r * cos(e).toFloat(); val ey = r * sin(e).toFloat()
            val tx = -sin(e).toFloat(); val ty = cos(e).toFloat()   // tangent, direction of travel
            val nx = cos(e).toFloat(); val ny = sin(e).toFloat()    // outward normal
            moveTo(ex - tx * 3.5f + nx * 2.6f, ey - ty * 3.5f + ny * 2.6f)
            lineTo(ex, ey)
            lineTo(ex - tx * 3.5f - nx * 2.6f, ey - ty * 3.5f - ny * 2.6f)
        }
    }
    val upper = Path().apply { moveTo(32f, 39f); quadraticTo(50f, 18f, 68f, 39f) }
    val lower = Path().apply { moveTo(68f, 53f); quadraticTo(50f, 74f, 32f, 53f) }

    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        drawPath(upper, c, alpha = (a * 0.3f).coerceIn(0f, 1f), style = ThinStroke)
        drawPath(lower, c, alpha = (a * 0.3f).coerceIn(0f, 1f), style = ThinStroke)
        drawNode(c, a, 24f, spin * 2f)
        drawNode(c, a, 76f, -spin * 2f)
        rotate(spin, Offset(50f, 46f)) { translate(50f, 46f) { glowPath(arrows, c, a) } }
        spark(c, a, Offset(50f, 46f), 1.4f)
        // Packets: up and over left to right, down and under right to left.
        for (k in 0..2) {
            val f = frac(flow * 0.5f + k / 3f)
            spark(c, a * fade(f), quad(32f, 39f, 50f, 18f, 68f, 39f, f), 1.3f)
            spark(c, a * fade(f), quad(68f, 53f, 50f, 74f, 32f, 53f, f), 1.3f)
        }
    }

    /** A device: hex frame, tiny screen glyph and an orbiting tracking arc. */
    private fun DrawScope.drawNode(c: Color, a: Float, x: Float, orbit: Float) {
        translate(x, 46f) {
            drawPath(node, c, alpha = (a * 0.1f).coerceIn(0f, 1f))
            glowPath(node, c, a)
            drawRect(c, Offset(-4f, -3f), Size(8f, 5f), alpha = (a * 0.8f).coerceIn(0f, 1f), style = ThinStroke)
            drawLine(c, Offset(-2f, 4f), Offset(2f, 4f), 0.9f, alpha = (a * 0.8f).coerceIn(0f, 1f))
            glowArc(c, a * 0.5f, orbit, 70f, Offset.Zero, 12.5f)
        }
    }
}

private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, t: Float): Offset {
    val u = 1f - t
    return Offset(u * u * x0 + 2f * u * t * x1 + t * t * x2, u * u * y0 + 2f * u * t * y1 + t * t * y2)
}

/** Nested screens around a spinning reel with a play triangle; light rays and a zoom ripple. */
private class MediaScreens : Figure() {
    val outer = screen(64f, 48f)
    val mid = screen(50f, 38f)
    val inner = screen(36f, 28f)
    val play = Path().apply { moveTo(47f, 40f); lineTo(47f, 48f); lineTo(54f, 44f); close() }

    private fun screen(w: Float, h: Float) = Path().apply {
        addRoundRect(RoundRect(50f - w / 2f, 44f - h / 2f, 50f + w / 2f, 44f + h / 2f, CornerRadius(3f)))
    }

    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        glowPath(outer, c, a * 0.3f)
        glowPath(mid, c, a * 0.55f)
        drawPath(inner, c, alpha = (a * 0.08f).coerceIn(0f, 1f))
        glowPath(inner, c, a)
        // Corner brackets on the outer screen.
        for (corner in 0 until 4) {
            val sx = if (corner % 2 == 0) -1f else 1f
            val sy = if (corner < 2) -1f else 1f
            val x = 50f + sx * 32f; val y = 44f + sy * 24f
            drawLine(c, Offset(x, y), Offset(x - sx * 5f, y), 1.6f, alpha = a.coerceIn(0f, 1f))
            drawLine(c, Offset(x, y), Offset(x, y - sy * 5f), 1.6f, alpha = a.coerceIn(0f, 1f))
        }
        val ripple = frac(flow * 0.5f)
        scale(1f + ripple * 0.8f, Offset(50f, 44f)) { drawPath(inner, c, alpha = (a * (1f - ripple) * 0.5f).coerceIn(0f, 1f), style = ThinStroke) }
        for (i in 0 until 8) {
            val ang = (PI / 4 * i + PI / 8).toFloat()
            val ra = a * (0.25f + 0.3f * sin(2f * PI.toFloat() * (flow + i / 8f)))
            val dx = cos(ang); val dy = sin(ang)
            drawLine(c, Offset(50f + dx * 13f, 44f + dy * 13f), Offset(50f + dx * 24f, 44f + dy * 19f), 0.8f, alpha = ra.coerceIn(0f, 1f))
        }
        glowCircle(c, a, Offset(50f, 44f), 12f)
        for (i in 0 until 5) {
            val ang = Math.toRadians((spin + 72f * i).toDouble())
            drawCircle(c, 1.7f, Offset(50f + 8.5f * cos(ang).toFloat(), 44f + 8.5f * sin(ang).toFloat()), (a * 0.8f).coerceIn(0f, 1f), ThinStroke)
        }
        drawPath(play, c, alpha = (a * 0.4f).coerceIn(0f, 1f))
        glowPath(play, c, a)
    }
}

/** Shield with keyhole inside counter-rotating lock rings. */
private class VaultShield : Figure() {
    val shield = Path().apply {
        moveTo(50f, 22f); lineTo(68f, 28f); lineTo(68f, 44f); quadraticTo(68f, 60f, 50f, 70f)
        quadraticTo(32f, 60f, 32f, 44f); lineTo(32f, 28f); close()
    }
    val innerShield = Path().apply {
        moveTo(50f, 27f); lineTo(63f, 31.5f); lineTo(63f, 44f); quadraticTo(63f, 56f, 50f, 64f)
        quadraticTo(37f, 56f, 37f, 44f); lineTo(37f, 31.5f); close()
    }
    val keyhole = Path().apply {
        addOval(Rect(Offset(50f, 41f), 4f))
        moveTo(48.2f, 44f); lineTo(46.5f, 53f); lineTo(53.5f, 53f); lineTo(51.8f, 44f); close()
    }

    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        val center = Offset(50f, 46f)
        rotate(spin, center) {
            for (i in 0 until 4) {
                glowArc(c, a * 0.8f, i * 90f + 10f, 60f, center, 27f)
                val m = Math.toRadians((i * 90f + 40f).toDouble())
                spark(c, a, Offset(50f + 27f * cos(m).toFloat(), 46f + 27f * sin(m).toFloat()), 1.1f)
            }
        }
        rotate(-spin * 1.5f, center) {
            for (i in 0 until 12) drawArc(c, i * 30f, 14f, false, Offset(50f - 31.5f, 46f - 31.5f), Size(63f, 63f), (a * 0.5f).coerceIn(0f, 1f), ThinStroke)
        }
        drawPath(shield, c, alpha = (a * 0.1f).coerceIn(0f, 1f))
        glowPath(shield, c, a)
        drawPath(innerShield, c, alpha = (a * 0.45f).coerceIn(0f, 1f), style = ThinStroke)
        drawPath(keyhole, c, alpha = (a * 0.5f).coerceIn(0f, 1f))
        glowPath(keyhole, c, a)
    }
}

/** Fallback: a small rack of server units with blinking status lights. */
/** A page with a folded corner, a cutting beam sweeping down it, and pages splitting off to the sides. */
private class PdfToolkit : Figure() {
    val page = Path().apply {
        moveTo(36f, 20f); lineTo(58f, 20f); lineTo(66f, 28f); lineTo(66f, 64f); lineTo(36f, 64f); close()
        moveTo(58f, 20f); lineTo(58f, 28f); lineTo(66f, 28f)
    }
    val mini = Path().apply { moveTo(0f, 0f); lineTo(10f, 0f); lineTo(13f, 3f); lineTo(13f, 17f); lineTo(0f, 17f); close() }

    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        // Pages drifting out to both sides: split / merge.
        for (side in listOf(-1f, 1f)) {
            val f = frac(flow * 0.6f + if (side > 0) 0.5f else 0f)
            val da = a * fade(f) * 0.8f
            translate(44.5f + side * (14f + f * 12f), 34f + f * 6f) { glowPath(mini, c, da) }
        }
        drawPath(page, c, alpha = (a * 0.1f).coerceIn(0f, 1f))
        glowPath(page, c, a)
        // Text lines and the "PDF" badge.
        for (l in 0..3) drawLine(c, Offset(40f, 34f + l * 5f), Offset(60f - (l % 2) * 6f, 34f + l * 5f), 0.9f, alpha = (a * 0.6f).coerceIn(0f, 1f))
        drawRect(c, Offset(40f, 54f), androidx.compose.ui.geometry.Size(12f, 6f), alpha = (a * 0.35f).coerceIn(0f, 1f))
        // Processing beam.
        val y = 22f + frac(spin * 0.5f) * 40f
        drawLine(c, Offset(32f, y), Offset(70f, y), 1.6f, alpha = (a * 0.9f).coerceIn(0f, 1f))
        drawLine(c, Offset(32f, y), Offset(70f, y), 5f, alpha = (a * 0.15f).coerceIn(0f, 1f))
    }
}

/** Shared screen outline for the developer figures: a window with a title bar. */
private fun windowPath() = Path().apply {
    moveTo(26f, 24f); lineTo(74f, 24f); lineTo(74f, 62f); lineTo(26f, 62f); close()
    moveTo(26f, 31f); lineTo(74f, 31f)
}

private fun DrawScope.titleDots(c: Color, a: Float) {
    for (i in 0..2) drawCircle(c, 1.2f, Offset(30f + i * 4f, 27.5f), alpha = (a * 0.8f).coerceIn(0f, 1f))
}

/** Terminal prompt with a blinking cursor and output lines scrolling up. */
private class TerminalWindow : Figure() {
    val frame = windowPath()
    val prompt = Path().apply { moveTo(31f, 50f); lineTo(36f, 54f); lineTo(31f, 58f) }

    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        drawPath(frame, c, alpha = (a * 0.08f).coerceIn(0f, 1f))
        glowPath(frame, c, a)
        titleDots(c, a)
        for (l in 0..2) {
            val y = 36f + l * 4.5f - frac(flow) * 4.5f
            if (y > 33f) drawLine(c, Offset(31f, y), Offset(31f + 12f + ((l * 7) % 5) * 5f, y), 0.9f, alpha = (a * 0.55f).coerceIn(0f, 1f))
        }
        glowPath(prompt, c, a)
        if (frac(spin * 2f) < 0.5f) drawRect(c, Offset(39f, 52.5f), androidx.compose.ui.geometry.Size(6f, 4f), alpha = (a * 0.9f).coerceIn(0f, 1f))
    }
}

/** Editor window with code brackets and highlighted lines being typed. */
private class CodeWindow : Figure() {
    val frame = windowPath()
    val left = Path().apply { moveTo(42f, 38f); lineTo(36f, 45f); lineTo(42f, 52f) }
    val right = Path().apply { moveTo(58f, 38f); lineTo(64f, 45f); lineTo(58f, 52f) }
    val slash = Path().apply { moveTo(53f, 37f); lineTo(47f, 53f) }

    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        drawPath(frame, c, alpha = (a * 0.08f).coerceIn(0f, 1f))
        glowPath(frame, c, a)
        titleDots(c, a)
        glowPath(left, c, a); glowPath(right, c, a); glowPath(slash, Hud.Gold, a)
        val typed = frac(flow * 0.7f)
        drawLine(c, Offset(31f, 57f), Offset(31f + 36f * typed, 57f), 1f, alpha = (a * 0.7f).coerceIn(0f, 1f))
    }
}

/** Browser window with a wireframe globe: websites served from this device. */
private class BrowserWindow : Figure() {
    val frame = windowPath()

    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        drawPath(frame, c, alpha = (a * 0.08f).coerceIn(0f, 1f))
        glowPath(frame, c, a)
        titleDots(c, a)
        drawLine(c, Offset(44f, 27.5f), Offset(70f, 27.5f), 1f, alpha = (a * 0.4f).coerceIn(0f, 1f))
        val center = Offset(50f, 46.5f)
        glowCircle(c, a, center, 11f)
        // Meridian ellipses turning around the globe.
        for (k in 0..1) {
            val w = 11f * kotlin.math.abs(kotlin.math.cos((spin + k * 0.25f) * 2f * Math.PI.toFloat()))
            drawOval(c, Offset(center.x - w, center.y - 11f), androidx.compose.ui.geometry.Size(w * 2f, 22f),
                alpha = (a * 0.6f).coerceIn(0f, 1f), style = Stroke(0.9f))
        }
        drawLine(c, Offset(39f, 46.5f), Offset(61f, 46.5f), 0.9f, alpha = (a * 0.6f).coerceIn(0f, 1f))
    }
}

/** Three stacked database discs with a query pulse travelling down. */
private class DatabaseStack : Figure() {
    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        val pulse = frac(flow * 0.8f)
        for (i in 0..2) {
            val top = 26f + i * 12f
            val lit = kotlin.math.abs(pulse * 3f - i - 0.5f) < 0.5f
            val da = if (lit) a else a * 0.6f
            drawOval(c, Offset(33f, top), androidx.compose.ui.geometry.Size(34f, 9f), alpha = (da * 0.12f).coerceIn(0f, 1f))
            drawOval(c, Offset(33f, top), androidx.compose.ui.geometry.Size(34f, 9f), alpha = (da * 0.9f).coerceIn(0f, 1f), style = Stroke(1.2f))
            drawLine(c, Offset(33f, top + 4.5f), Offset(33f, top + 13.5f), 1.2f, alpha = (da * 0.9f).coerceIn(0f, 1f))
            drawLine(c, Offset(67f, top + 4.5f), Offset(67f, top + 13.5f), 1.2f, alpha = (da * 0.9f).coerceIn(0f, 1f))
            if (lit) spark(Hud.Gold, a, Offset(50f, top + 4.5f), 1.6f)
        }
        drawOval(c, Offset(33f, 50f), androidx.compose.ui.geometry.Size(34f, 9f), alpha = (a * 0.9f).coerceIn(0f, 1f), style = Stroke(1.2f))
    }
}

private class ServerRack : Figure() {
    val frame = Path().apply {
        addRect(Rect(34f, 20f, 66f, 72f))
        for (i in 0 until 4) addRect(Rect(37f, 23f + i * 12.5f, 63f, 33f + i * 12.5f))
    }

    override fun DrawScope.draw(c: Color, a: Float, spin: Float, flow: Float) {
        drawPath(frame, c, alpha = (a * 0.08f).coerceIn(0f, 1f))
        glowPath(frame, c, a)
        for (i in 0 until 4) {
            val y = 23f + i * 12.5f
            for (v in 0..2) drawLine(c, Offset(48f, y + 3f + v * 2f), Offset(60f, y + 3f + v * 2f), 0.7f, alpha = (a * 0.5f).coerceIn(0f, 1f))
            val on = noise((flow * 4f).toInt() + i * 7) > 0.35f
            spark(c, if (on) a else a * 0.25f, Offset(41f, y + 5f), 1.3f)
        }
        glowLine(c, a * 0.6f, Offset(38f, 72f), Offset(38f, 76f))
        glowLine(c, a * 0.6f, Offset(62f, 72f), Offset(62f, 76f))
    }
}

// ---------- Previews ----------

private val PreviewApps = listOf("filebrowser", "syncthing", "jellyfin", "vaultwarden", "unknown")

@Composable
private fun HologramGrid() {
    Column(Modifier.background(Hud.Void).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreviewApps.forEach { id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(id.uppercase(), style = HudType.Label.copy(fontSize = 9.sp), modifier = Modifier.width(96.dp))
                HologramState.entries.forEach { st ->
                    AppHologram(id, st, Modifier.size(88.dp))
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Preview(name = "Holograms", widthDp = 520, heightDp = 540)
@Composable
private fun AppHologramPreview() = HologramGrid()

@Preview(name = "Holograms (reduced motion)", widthDp = 520, heightDp = 540)
@Composable
private fun AppHologramReducedPreview() {
    CompositionLocalProvider(LocalMotion provides Motion.Reduced) { HologramGrid() }
}
