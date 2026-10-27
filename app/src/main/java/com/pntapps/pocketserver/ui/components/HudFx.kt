package com.pntapps.pocketserver.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pntapps.pocketserver.ui.motion.LocalMotion
import com.pntapps.pocketserver.ui.motion.animated
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/*
 * Motion effects for the HUD. Rules every effect here follows:
 *  - One draw node per effect; animated values are read only inside draw lambdas, so a running
 *    effect redraws its own layer each frame and never recomposes.
 *  - Paths, brushes and text layouts are built in drawWithCache and reused across frames.
 *  - With Motion.Reduced no loop is started at all and each effect draws a composed still frame.
 */

/**
 * Looping phase in 0..1 that wraps every [periodMs] (scaled by [speed]). Driven by the frame clock,
 * so it pauses with the app; changing [speed] keeps the phase continuous. When [running] is false
 * nothing is launched and the phase stays at 0. Sub-effects use whole multiples of the phase so the
 * wrap point is seamless.
 */
@Composable
private fun rememberLoopPhase(running: Boolean, periodMs: Int, speed: Float = 1f): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val rate by rememberUpdatedState(speed / (periodMs * 1_000_000f))
    if (running) LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            // Clamp so returning from the background doesn't make the animation leap.
            val dt = (now - last).coerceAtMost(100_000_000L)
            last = now
            val p = phase.floatValue + dt * rate
            phase.floatValue = p - floor(p)
        }
    }
    return phase
}

private const val TAU = (2 * PI).toFloat()

// ---------------------------------------------------------------------------------------------
// Live background
// ---------------------------------------------------------------------------------------------

/** Per particle: x, y (0..1), drift cycles per loop, radius (dp), twinkle cycles, phase offset, gold flag. */
private val Particles: FloatArray = Random(7).let { rnd ->
    FloatArray(30 * 7).also { a ->
        for (i in 0 until 30) {
            a[i * 7] = rnd.nextFloat()
            a[i * 7 + 1] = rnd.nextFloat()
            a[i * 7 + 2] = (1 + rnd.nextInt(2)).toFloat()
            a[i * 7 + 3] = 0.6f + rnd.nextFloat() * 1.2f
            a[i * 7 + 4] = (3 + rnd.nextInt(6)).toFloat()
            a[i * 7 + 5] = rnd.nextFloat()
            a[i * 7 + 6] = if (rnd.nextFloat() < 0.2f) 1f else 0f
        }
    }
}

/**
 * Animated replacement for [hudBackground]: deep radial gradient, a two-layer blueprint grid
 * drifting slowly at different speeds (parallax), a scan band sweeping down every 6 s, ~30 drifting
 * motes and a vignette. Reduced motion draws the same scene frozen.
 *
 * Everything after this modifier (padding, children) is placed in its own graphics layer, so the
 * per-frame redraw re-records only the backdrop, never the screen content above it.
 */
fun Modifier.hudLiveBackground(): Modifier = composed {
    val phase = rememberLoopPhase(LocalMotion.current.animated, 60_000)
    background(Hud.Void)
        .drawWithCache {
            val w = size.width; val h = size.height
            val glow = Brush.radialGradient(listOf(Hud.Deep, Hud.Void), Offset(w * 0.3f, h * 0.2f), size.maxDimension)
            val glow2 = Brush.radialGradient(listOf(Hud.Cyan.copy(alpha = 0.045f), Color.Transparent), Offset(w * 0.85f, h * 0.9f), size.maxDimension * 0.55f)
            val vignette = Brush.radialGradient(
                0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f),
                center = size.center, radius = size.maxDimension * 0.72f,
            )
            val band = 140.dp.toPx()
            val scan = Brush.verticalGradient(
                0f to Color.Transparent, 0.8f to Hud.Cyan.copy(alpha = 0.035f), 0.985f to Hud.Cyan.copy(alpha = 0.09f), 1f to Color.Transparent,
                startY = 0f, endY = band,
            )
            val fine = 40.dp.toPx(); val major = 160.dp.toPx(); val cross = 5.dp.toPx(); val dp = 1.dp.toPx()
            val fineColor = Hud.Line.copy(alpha = 0.16f); val majorColor = Hud.Line.copy(alpha = 0.32f)
            onDrawBehind {
                val p = phase.value
                drawRect(glow); drawRect(glow2)
                // Far layer: fine grid drifts one cell per minute.
                val o = p * fine
                var x = o - fine
                while (x < w) { drawLine(fineColor, Offset(x, 0f), Offset(x, h), 1f); x += fine }
                var y = o - fine
                while (y < h) { drawLine(fineColor, Offset(0f, y), Offset(w, y), 1f); y += fine }
                // Near layer: sparse crosshair marks drift faster (one major cell per minute).
                val m = p * major
                x = m - major
                while (x < w + major) {
                    y = m * 0.5f - major
                    while (y < h + major) {
                        drawLine(majorColor, Offset(x - cross, y), Offset(x + cross, y), dp)
                        drawLine(majorColor, Offset(x, y - cross), Offset(x, y + cross), dp)
                        y += major
                    }
                    x += major
                }
                // Scan band: 3.6 s sweep, then 2.4 s rest, ten times per loop.
                val s = (p * 10f).let { it - floor(it) }
                if (s < 0.6f) {
                    val top = (s / 0.6f) * (h + band) - band
                    translate(top = top) { drawRect(scan, size = Size(w, band)) }
                    drawLine(Hud.Cyan.copy(alpha = 0.16f), Offset(0f, top + band), Offset(w, top + band), dp)
                }
                // Motes rise slowly with a sideways wobble and twinkle.
                for (i in 0 until 30) {
                    val b = i * 7
                    val py = (Particles[b + 1] - p * Particles[b + 2]).let { it - floor(it) }
                    val px = Particles[b] + 0.012f * sin(TAU * (p * 2f + Particles[b + 5]))
                    val tw = 0.25f + 0.55f * (0.5f + 0.5f * sin(TAU * (p * Particles[b + 4] + Particles[b + 5])))
                    val c = if (Particles[b + 6] > 0f) Hud.Gold else Hud.Cyan
                    drawCircle(c.copy(alpha = tw), Particles[b + 3] * dp, Offset(px * w, py * h))
                }
                drawRect(vignette)
            }
        }
        .graphicsLayer()
}

// ---------------------------------------------------------------------------------------------
// Decrypt text
// ---------------------------------------------------------------------------------------------

private const val GLYPHS = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789#%&*+<>/="

private fun scrambled(text: String, resolved: Int, dim: Color) = buildAnnotatedString {
    append(text, 0, resolved)
    withStyle(SpanStyle(color = dim)) {
        for (i in resolved until text.length) {
            val c = text[i]
            append(if (c.isLetterOrDigit()) GLYPHS[Random.nextInt(GLYPHS.length)] else c)
        }
    }
}

/**
 * "Decrypt" text: characters resolve left-to-right out of random glyphs when first shown and
 * whenever [text] changes. Only this composable recomposes while it runs (~25 times a second for
 * [durationMs]); the final text reserves the layout size, so nothing around it jitters.
 * Plain [Text] when motion is reduced.
 */
@Composable
fun ScrambleText(text: String, style: TextStyle, modifier: Modifier = Modifier, durationMs: Int = 700) {
    if (!LocalMotion.current.animated) { Text(text, modifier, style = style); return }
    val dim = style.color.takeOrElse { Hud.Text }.let { it.copy(alpha = it.alpha * 0.5f) }
    var shown by remember(text) { mutableStateOf(scrambled(text, 0, dim)) }
    LaunchedEffect(text) {
        val start = withFrameMillis { it }
        var lastSwap = start
        while (true) {
            val now = withFrameMillis { it }
            val elapsed = now - start
            if (elapsed >= durationMs) break
            if (now - lastSwap >= 40) {
                lastSwap = now
                shown = scrambled(text, (text.length * elapsed / durationMs).toInt(), dim)
            }
        }
        shown = AnnotatedString(text)
    }
    Box(modifier) {
        Text(text, Modifier.alpha(0f), style = style)
        Text(shown, Modifier.matchParentSize().clearAndSetSemantics {}, style = style, overflow = TextOverflow.Clip)
    }
}

// ---------------------------------------------------------------------------------------------
// Panel entrance
// ---------------------------------------------------------------------------------------------

/**
 * When the current screen was first composed (uptime ms), provided per screen by the shell; -1 when
 * no screen provides one (previews), which makes every [hudReveal] play.
 */
val LocalRevealEpoch = staticCompositionLocalOf { -1L }

/** Panels composing later than this after their screen appeared (scrolled into view) skip the reveal. */
private const val REVEAL_WINDOW_MS = 1_500L

/**
 * Panel entrance: after `index * 70 ms` the panel fades in while unfolding from its top edge, and a
 * bright line draws along the top edge of the [hudFrame] (starting after the [cut] chamfer) then
 * fades. Plays only for panels that compose within [REVEAL_WINDOW_MS] of their screen appearing
 * ([LocalRevealEpoch]): lazy items scrolled back into view show at once instead of waiting
 * invisible. No-op with reduced motion.
 * Apply before [hudFrame]/[HudPanel]'s own modifier so the frame is inside the animated layer.
 */
fun Modifier.hudReveal(index: Int = 0, accent: Color = Hud.Cyan, cut: Dp = 14.dp): Modifier = composed {
    if (!LocalMotion.current.animated) return@composed this
    val epoch = LocalRevealEpoch.current
    val late = remember { epoch >= 0 && SystemClock.uptimeMillis() - epoch > REVEAL_WINDOW_MS }
    if (late) return@composed this
    val enter = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceIn(0, 12) * 70L)
        launch { enter.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
        sweep.animateTo(1f, tween(700, easing = LinearOutSlowInEasing))
    }
    graphicsLayer {
        val v = enter.value
        alpha = v
        scaleY = 0.94f + 0.06f * v
        translationY = (1f - v) * 14.dp.toPx()
        transformOrigin = TransformOrigin(0.5f, 0f)
    }.drawWithContent {
        drawContent()
        val s = sweep.value
        if (s >= 1f) return@drawWithContent
        val c = cut.toPx()
        val head = c + (size.width - c) * (s / 0.7f).coerceAtMost(1f)
        val a = if (s < 0.7f) 1f else (1f - s) / 0.3f
        val start = Offset(c, 0f); val end = Offset(head, 0f)
        drawLine(accent.copy(alpha = 0.18f * a), start, end, 7.dp.toPx(), StrokeCap.Round)
        drawLine(accent.copy(alpha = 0.9f * a), start, end, 1.5.dp.toPx())
        drawCircle(Color.White.copy(alpha = 0.9f * a), 2.dp.toPx(), end)
        drawCircle(accent.copy(alpha = 0.3f * a), 7.dp.toPx(), end)
    }
}

/**
 * Brief glow flash from the touch point when [interactionSource] reports a press. Put it after any
 * clip so the glow follows the button shape. No-op with reduced motion.
 */
fun Modifier.hudPressGlow(interactionSource: InteractionSource, color: Color = Hud.Cyan): Modifier = composed {
    if (!LocalMotion.current.animated) return@composed this
    val glow = remember { Animatable(0f) }
    val origin = remember { floatArrayOf(Float.NaN, Float.NaN) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            if (it is PressInteraction.Press) {
                origin[0] = it.pressPosition.x; origin[1] = it.pressPosition.y
                launch { glow.animateTo(1f, tween(90)); glow.animateTo(0f, tween(480)) }
            }
        }
    }
    drawWithContent {
        drawContent()
        val g = glow.value
        if (g <= 0.01f) return@drawWithContent
        val at = if (origin[0].isNaN()) center else Offset(origin[0], origin[1])
        // Allocates a brush only during the ~0.5 s flash.
        drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * g), Color.Transparent), at, size.maxDimension * (0.5f + 0.5f * g)))
    }
}

// ---------------------------------------------------------------------------------------------
// Radar
// ---------------------------------------------------------------------------------------------

/** A contact on [RadarSweep]: [angleDeg] clockwise from north, [distance] 0 (center) to 1 (rim). */
data class RadarBlip(val angleDeg: Float, val distance: Float, val color: Color, val label: String)

/**
 * Square radar scope: range rings, crosshair, bearing ticks and a sweep with a fading trail
 * (one turn per 4 s). Blips flare as the sweep passes and fade until the next pass; labels in mono.
 * Reduced motion freezes the sweep at a fixed bearing.
 */
@Composable
fun RadarSweep(blips: List<RadarBlip>, modifier: Modifier = Modifier, color: Color = Hud.Cyan) {
    val phase = rememberLoopPhase(LocalMotion.current.animated, 4_000)
    val measurer = rememberTextMeasurer(cacheSize = 16)
    Spacer(
        modifier.aspectRatio(1f).graphicsLayer().drawWithCache {
            val r = size.minDimension / 2 * 0.96f
            val c = size.center
            val dp = 1.dp.toPx()
            // dp-derived size: labels must fit inside the scope at any system font scale.
            val labelStyle = TextStyle(fontFamily = Mono, fontSize = (10 * dp).toSp(), letterSpacing = (0.5f * dp).toSp())
            val labels = blips.map { measurer.measure(it.label.uppercase(), labelStyle) }
            val trail = Brush.sweepGradient(0f to Color.Transparent, 0.74f to Color.Transparent, 1f to color.copy(alpha = 0.4f), center = c)
            // Static scope (rings, crosshair, ticks) is recorded once and replayed every frame.
            val scope = obtainGraphicsLayer().apply {
                record {
                    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.10f), Color.Transparent), c, r), r, c)
                    for (i in 1..4) drawCircle(color.copy(alpha = if (i == 4) 0.55f else 0.2f), r * i / 4f, c, style = Stroke(dp))
                    drawLine(color.copy(alpha = 0.2f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), dp)
                    drawLine(color.copy(alpha = 0.2f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), dp)
                    for (i in 0 until 72) {
                        val a = TAU * i / 72
                        val len = if (i % 6 == 0) r * 0.07f else r * 0.03f
                        val dx = cos(a); val dy = sin(a)
                        drawLine(color.copy(alpha = if (i % 6 == 0) 0.6f else 0.3f), Offset(c.x + dx * (r - len), c.y + dy * (r - len)), Offset(c.x + dx * r, c.y + dy * r), dp)
                    }
                }
            }
            onDrawBehind {
                drawLayer(scope)
                val sweep = (phase.value * 360f + 300f) % 360f
                rotate(sweep, c) {
                    drawCircle(trail, r, c)
                    drawLine(color.copy(alpha = 0.25f), c, Offset(c.x + r, c.y), 5 * dp)
                    drawLine(color, c, Offset(c.x + r, c.y), 1.5f * dp)
                }
                blips.forEachIndexed { i, b ->
                    val bearing = b.angleDeg - 90f
                    val since = ((sweep - bearing) % 360f + 360f) % 360f
                    val lit = 0.3f + 0.7f * exp(-since / 80f)
                    val rad = bearing * (PI.toFloat() / 180f)
                    val d = b.distance.coerceIn(0f, 1f) * r * 0.92f
                    val pos = Offset(c.x + cos(rad) * d, c.y + sin(rad) * d)
                    if (since < 100f) {
                        val k = since / 100f
                        drawCircle(b.color.copy(alpha = 0.6f * (1f - k)), (3f + 11f * k) * dp, pos, style = Stroke(dp))
                    }
                    drawCircle(b.color.copy(alpha = 0.22f * lit), 7 * dp * lit, pos)
                    drawCircle(b.color.copy(alpha = lit), 2.5f * dp, pos)
                    val l = labels[i]
                    if (l.size.width == 0) return@forEachIndexed
                    // Right-half blips are labelled on their left, so labels run towards the center;
                    // then the label is clamped to the chord of the outer ring at its farthest row.
                    val lw = l.size.width.toFloat(); val lh = l.size.height.toFloat()
                    val ly = (pos.y - lh / 2f).coerceIn(c.y - r * 0.9f, c.y + r * 0.9f - lh)
                    val far = maxOf(abs(ly - c.y), abs(ly + lh - c.y)).coerceAtMost(r)
                    val half = sqrt(r * r - far * far) - 2 * dp
                    val wanted = if (pos.x > c.x) pos.x - 8 * dp - lw else pos.x + 8 * dp
                    val lx = if (lw >= half * 2) c.x - lw / 2f else wanted.coerceIn(c.x - half, c.x + half - lw)
                    drawText(l, color = b.color, topLeft = Offset(lx, ly), alpha = 0.35f + 0.65f * lit)
                }
                drawCircle(color, 2 * dp, c)
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Wireframe globe
// ---------------------------------------------------------------------------------------------

private fun ringPoints(segments: Int, point: (Float) -> Triple<Float, Float, Float>) = FloatArray((segments + 1) * 3).also { a ->
    for (i in 0..segments) {
        val (x, y, z) = point(i.toFloat() / segments)
        a[i * 3] = x; a[i * 3 + 1] = y; a[i * 3 + 2] = z
    }
}

/** Unit-sphere polylines: 5 parallels and 12 half meridians. */
private val GlobeLines: List<FloatArray> = buildList {
    for (lat in listOf(-60f, -30f, 0f, 30f, 60f)) {
        val la = lat * PI.toFloat() / 180f
        add(ringPoints(40) { t -> Triple(cos(la) * cos(TAU * t), sin(la), cos(la) * sin(TAU * t)) })
    }
    for (m in 0 until 12) {
        val lo = TAU * m / 12
        add(ringPoints(20) { t -> val la = (t - 0.5f) * PI.toFloat(); Triple(cos(la) * cos(lo), sin(la), cos(la) * sin(lo)) })
    }
}

/** Inclined orbit of radius 1.45, already in world space (it does not spin with the globe). */
private val OrbitLine: FloatArray = ringPoints(64) { t -> orbitPoint(t) }

private fun orbitPoint(t: Float): Triple<Float, Float, Float> {
    val x = 1.45f * cos(TAU * t); val z = 1.45f * sin(TAU * t)
    val inc = 0.5f; val roll = -0.35f
    val y1 = -z * sin(inc); val z1 = z * cos(inc)
    return Triple(x * cos(roll) - y1 * sin(roll), x * sin(roll) + y1 * cos(roll), z1)
}

/** Surface nodes (lat, lon in degrees) that blink as they rotate past the front. */
private val GlobeNodes = floatArrayOf(40f, 10f, 15f, 75f, -20f, 140f, 55f, 200f, -40f, 250f, 5f, 310f, 25f, 170f)

private const val TILT = 0.38f

/** Projects a polyline (yawed by [yawC]/[yawS], tilted by [TILT]) into [front] or [back] per segment. */
private fun projectInto(line: FloatArray, yawC: Float, yawS: Float, cx: Float, cy: Float, r: Float, front: Path, back: Path) {
    val tc = cos(TILT); val ts = sin(TILT)
    var px = 0f; var py = 0f; var pz = 0f
    for (i in 0 until line.size / 3) {
        val x = line[i * 3]; val y = line[i * 3 + 1]; val z = line[i * 3 + 2]
        val x1 = x * yawC + z * yawS
        val z1 = -x * yawS + z * yawC
        val y2 = y * tc - z1 * ts
        val z2 = y * ts + z1 * tc
        val sx = cx + r * x1; val sy = cy - r * y2
        if (i > 0) {
            val path = if (pz + z2 >= 0f) front else back
            path.moveTo(px, py); path.lineTo(sx, sy)
        }
        px = sx; py = sy; pz = z2
    }
}

/**
 * Slowly rotating wireframe planet (front lines bright, back lines faint) with an inclined orbit
 * and a gold satellite node. [active] = full brightness and a 24 s turn; inactive is dimmer and
 * about three times slower. Reduced motion shows a fixed three-quarter view.
 */
@Composable
fun WireGlobe(modifier: Modifier = Modifier, active: Boolean) {
    val phase = rememberLoopPhase(LocalMotion.current.animated, 24_000, if (active) 1f else 0.35f)
    val line = if (active) Hud.Cyan else Hud.CyanDim
    val gold = if (active) Hud.Gold else Hud.Gold.copy(alpha = 0.5f)
    Spacer(
        modifier.aspectRatio(1f).graphicsLayer().drawWithCache {
            val c = size.center
            val r = size.minDimension / 2 * 0.6f
            val dp = 1.dp.toPx()
            val halo = Brush.radialGradient(
                0f to line.copy(alpha = if (active) 0.22f else 0.08f), 0.55f to line.copy(alpha = 0.06f), 1f to Color.Transparent,
                center = c, radius = r * 1.6f,
            )
            val core = Brush.radialGradient(listOf(Hud.Void.copy(alpha = 0.9f), Hud.Deep.copy(alpha = 0.6f)), c, r)
            val front = Path(); val back = Path(); val orbitFront = Path(); val orbitBack = Path()
            val thin = Stroke(dp); val glowStroke = Stroke(4 * dp)
            onDrawBehind {
                val p = phase.value
                val yaw = TAU * p + 0.6f
                val yc = cos(yaw); val ys = sin(yaw)
                front.rewind(); back.rewind(); orbitFront.rewind(); orbitBack.rewind()
                for (l in GlobeLines) projectInto(l, yc, ys, c.x, c.y, r, front, back)
                projectInto(OrbitLine, 1f, 0f, c.x, c.y, r, orbitFront, orbitBack)

                drawCircle(halo, r * 1.6f, c)
                drawPath(orbitBack, gold.copy(alpha = 0.18f), style = thin)
                drawCircle(core, r, c)
                drawPath(back, line.copy(alpha = if (active) 0.16f else 0.1f), style = thin)
                drawPath(front, line.copy(alpha = 0.15f), style = glowStroke)
                drawPath(front, line.copy(alpha = if (active) 0.8f else 0.5f), style = thin)
                drawCircle(line.copy(alpha = 0.6f), r, c, style = Stroke(1.5f * dp))

                // Surface nodes ride the rotation and blink on the visible hemisphere.
                val tc = cos(TILT); val ts = sin(TILT)
                for (n in 0 until GlobeNodes.size / 2) {
                    val la = GlobeNodes[n * 2] * PI.toFloat() / 180f; val lo = GlobeNodes[n * 2 + 1] * PI.toFloat() / 180f
                    val x = cos(la) * cos(lo); val y = sin(la); val z = cos(la) * sin(lo)
                    val x1 = x * yc + z * ys; val z1 = -x * ys + z * yc
                    val z2 = y * ts + z1 * tc
                    if (z2 <= 0f) continue
                    val pos = Offset(c.x + r * x1, c.y - r * (y * tc - z1 * ts))
                    val blink = 0.5f + 0.5f * sin(TAU * (p * (4 + n) + n * 0.3f))
                    drawCircle(Hud.Text.copy(alpha = z2 * (0.4f + 0.6f * blink)), 1.8f * dp, pos)
                    drawCircle(line.copy(alpha = z2 * 0.25f * blink), 6 * dp, pos)
                }

                drawPath(orbitFront, gold.copy(alpha = 0.2f), style = glowStroke)
                drawPath(orbitFront, gold.copy(alpha = 0.7f), style = thin)
                val (ox, oy, oz) = orbitPoint((p * 3f) % 1f)
                val node = Offset(c.x + r * ox, c.y - r * (oy * tc - oz * ts))
                val depth = if (oy * ts + oz * tc >= 0f) 1f else 0.35f
                drawCircle(gold.copy(alpha = 0.3f * depth), 8 * dp, node)
                drawCircle(gold.copy(alpha = depth), 3 * dp, node)
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Telemetry graph
// ---------------------------------------------------------------------------------------------

private class GraphStep(val dropped: Float, val progress: Animatable<Float, AnimationVector1D>)

/**
 * Oscilloscope trace of [values] (0..1, oldest first) with grid, glowing line, gradient fill and a
 * pulsing marker on the latest sample. When one value is appended (same window size) the trace
 * slides left by a step over 650 ms instead of jumping. Optional [label] top-left, latest % top-right.
 * Fills its modifier's size (give it a height). Reduced motion: no slide or pulse.
 */
@Composable
fun TelemetryGraph(values: List<Float>, modifier: Modifier = Modifier, color: Color = Hud.Cyan, label: String? = null) {
    val animated = LocalMotion.current.animated
    val previous = remember { arrayOfNulls<List<Float>>(1) }
    val step = remember(values) {
        val old = previous[0]
        previous[0] = values
        val slid = animated && old != null && old.size == values.size && values.size > 1 && old != values
        GraphStep(old?.firstOrNull() ?: 0f, Animatable(if (slid) 0f else 1f))
    }
    LaunchedEffect(step) { step.progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    val pulse = rememberLoopPhase(animated, 1_600)
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val labelSize = hudSmallLabel
    Spacer(
        modifier.graphicsLayer().drawWithCache {
            val dp = 1.dp.toPx()
            val style = TextStyle(fontFamily = Mono, fontSize = labelSize, letterSpacing = 1.sp)
            val labelText = label?.let { measurer.measure(it.uppercase(), style.copy(color = Hud.TextDim)) }
            val valueText = values.lastOrNull()?.let { measurer.measure("${(it.coerceIn(0f, 1f) * 100).toInt()}%", style.copy(color = color)) }
            val top = if (labelText != null || valueText != null) maxOf(labelText?.size?.height ?: 0, valueText?.size?.height ?: 0) + 4 * dp else 0f
            val gh = size.height - top
            val fill = Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0f)), startY = top, endY = size.height)
            val linePath = Path(); val fillPath = Path()
            val gridColor = color.copy(alpha = 0.1f)
            onDrawBehind {
                labelText?.let { drawText(it, topLeft = Offset.Zero) }
                valueText?.let { drawText(it, topLeft = Offset(size.width - it.size.width, 0f)) }
                for (i in 0..4) { val y = top + gh * i / 4f; drawLine(gridColor, Offset(0f, y), Offset(size.width, y), dp) }
                for (i in 0..8) { val x = size.width * i / 8f; drawLine(gridColor, Offset(x, top), Offset(x, size.height), dp) }
                drawLine(color.copy(alpha = 0.35f), Offset(0f, size.height), Offset(size.width, size.height), dp)
                val n = values.size
                if (n < 2) return@onDrawBehind
                val dx = size.width / (n - 1)
                val p = step.progress.value
                val shift = (1f - p) * dx
                fun yOf(v: Float) = top + gh * (1f - v.coerceIn(0f, 1f)) * 0.92f + gh * 0.04f
                linePath.rewind(); fillPath.rewind()
                val first = if (p < 1f) -1 else 0
                for (i in first until n) {
                    val x = i * dx + shift
                    val y = yOf(if (i < 0) step.dropped else values[i])
                    if (i == first) { linePath.moveTo(x, y); fillPath.moveTo(x, size.height); fillPath.lineTo(x, y) } else { linePath.lineTo(x, y); fillPath.lineTo(x, y) }
                }
                fillPath.lineTo((n - 1) * dx + shift, size.height); fillPath.close()
                clipRect(0f, top, size.width, size.height) {
                    drawPath(fillPath, fill)
                    drawPath(linePath, color.copy(alpha = 0.2f), style = Stroke(5 * dp))
                    drawPath(linePath, color, style = Stroke(1.5f * dp))
                }
                val m = Offset(minOf((n - 1) * dx + shift, size.width - 3 * dp), yOf(values[n - 1]))
                val k = pulse.value
                if (animated) drawCircle(color.copy(alpha = 0.6f * (1f - k)), (3f + 9f * k) * dp, m, style = Stroke(dp))
                drawCircle(color.copy(alpha = 0.3f), 6 * dp, m)
                drawCircle(Hud.Text, 2.5f * dp, m)
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Holo ring
// ---------------------------------------------------------------------------------------------

private fun DrawScope.outerRing(r: Float, color: Color) {
    val dp = 1.dp.toPx()
    drawCircle(color.copy(alpha = 0.25f), r * 0.99f, style = Stroke(dp))
    // Irregular segments read as "machined" rather than a dashed circle.
    var a = 0f; var i = 0
    while (a < 360f) {
        val len = if (i % 5 == 0) 34f else if (i % 2 == 0) 12f else 6f
        drawArc(color.copy(alpha = if (i % 5 == 0) 0.75f else 0.4f), a, len, false, Offset(center.x - r * 0.92f, center.y - r * 0.92f), Size(r * 1.84f, r * 1.84f), style = Stroke(r * 0.045f))
        a += len + 4f; i++
    }
    for (t in 0 until 120) {
        val ang = TAU * t / 120
        val len = if (t % 10 == 0) r * 0.06f else r * 0.025f
        drawLine(color.copy(alpha = 0.35f), Offset(center.x + cos(ang) * (r * 0.84f - len), center.y + sin(ang) * (r * 0.84f - len)), Offset(center.x + cos(ang) * r * 0.84f, center.y + sin(ang) * r * 0.84f), dp)
    }
}

private fun DrawScope.innerRing(r: Float, color: Color) {
    val dp = 1.dp.toPx()
    val tl = Offset(center.x - r * 0.7f, center.y - r * 0.7f); val sz = Size(r * 1.4f, r * 1.4f)
    for (k in 0 until 3) {
        drawArc(color.copy(alpha = 0.7f), k * 120f, 70f, false, tl, sz, style = Stroke(r * 0.025f))
        drawArc(Hud.Gold.copy(alpha = 0.85f), k * 120f + 82f, 14f, false, tl, sz, style = Stroke(r * 0.05f))
    }
    for (k in 0 until 48) if (k % 4 != 0) drawArc(color.copy(alpha = 0.3f), k * 7.5f, 4f, false, Offset(center.x - r * 0.6f, center.y - r * 0.6f), Size(r * 1.2f, r * 1.2f), style = Stroke(dp * 2))
}

/**
 * Decorative segmented ring for behind headings and emblems: an outer machined ring turning
 * clockwise (40 s) and an inner arc/gold-notch ring turning the other way twice as fast. Both layers
 * are recorded once and only rotated per frame. Reduced motion: still, slightly offset layers.
 */
@Composable
fun HoloRing(modifier: Modifier = Modifier, color: Color = Hud.Cyan) {
    val phase = rememberLoopPhase(LocalMotion.current.animated, 40_000)
    Spacer(
        modifier.aspectRatio(1f).graphicsLayer().drawWithCache {
            val r = size.minDimension / 2
            val glow = Brush.radialGradient(listOf(color.copy(alpha = 0.12f), Color.Transparent), size.center, r)
            val outer = obtainGraphicsLayer().apply { record { outerRing(r, color) } }
            val inner = obtainGraphicsLayer().apply { record { innerRing(r, color) } }
            onDrawBehind {
                val a = phase.value * 360f
                drawCircle(glow, r)
                rotate(a + 8f) { drawLayer(outer) }
                rotate(-2f * a - 20f) { drawLayer(inner) }
            }
        },
    )
}
