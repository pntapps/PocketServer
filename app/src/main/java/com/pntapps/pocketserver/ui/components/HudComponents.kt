package com.pntapps.pocketserver.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pntapps.pocketserver.ui.motion.LocalMotion
import com.pntapps.pocketserver.ui.motion.animated
import com.pntapps.pocketserver.ui.theme.Hud
import com.pntapps.pocketserver.ui.theme.HudType
import kotlin.math.cos
import kotlin.math.sin

/** Panel outline with the top-left and bottom-right corners cut off. */
fun chamferPath(size: Size, cut: Float) = Path().apply {
    moveTo(cut, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height - cut)
    lineTo(size.width - cut, size.height)
    lineTo(0f, size.height)
    lineTo(0f, cut)
    close()
}

fun chamferShape(cut: Dp) = GenericShape { size, _ -> addPath(chamferPath(size, cut.value * 2.75f)) }

/** Translucent panel, glowing outline and corner brackets: the base look of every HUD surface. */
fun Modifier.hudFrame(accent: Color = Hud.Cyan, cut: Dp = 14.dp, fill: Color = Hud.Panel) = drawBehind {
    val c = cut.toPx()
    val path = chamferPath(size, c)
    // Opaque fills (dialogs on Hud.PanelSolid) stay opaque; translucent ones fade towards the bottom.
    if (fill.alpha >= 1f) drawPath(path, fill)
    else drawPath(path, Brush.verticalGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.6f))))
    drawPath(path, accent.copy(alpha = 0.10f), style = Stroke(6.dp.toPx()))
    drawPath(path, accent.copy(alpha = 0.55f), style = Stroke(1.dp.toPx()))
    val b = 18.dp.toPx(); val w = 2.dp.toPx()
    // Brackets on the two square corners.
    drawLine(accent, Offset(size.width - b, 0f), Offset(size.width, 0f), w)
    drawLine(accent, Offset(size.width, 0f), Offset(size.width, b), w)
    drawLine(accent, Offset(0f, size.height - b), Offset(0f, size.height), w)
    drawLine(accent, Offset(0f, size.height), Offset(b, size.height), w)
}

/** Smallest secondary text (chips, readout labels, trace labels): 11 sp on phones, 10 sp on tablets. */
val hudSmallLabel: TextUnit
    @Composable get() = if (LocalConfiguration.current.screenWidthDp >= 840) 10.sp else 11.sp

/** Static backdrop: deep gradient with a faint blueprint grid. Drawn once, no animation. */
fun Modifier.hudBackground() = background(Hud.Void).drawBehind {
    drawRect(Brush.radialGradient(listOf(Hud.Deep, Hud.Void), center = Offset(size.width * 0.3f, size.height * 0.2f), radius = size.maxDimension))
    val step = 40.dp.toPx()
    val grid = Hud.Line.copy(alpha = 0.18f)
    var x = 0f
    while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = 0f
    while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
}

@Composable
fun HudLabel(text: String, modifier: Modifier = Modifier, color: Color = Hud.Cyan) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = HudType.Label.copy(color = color))
    }
}

@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = Hud.Cyan,
    trailing: @Composable (() -> Unit)? = null,
    fill: Color = Hud.Panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.hudFrame(accent, fill = fill).padding(horizontal = 18.dp, vertical = 16.dp)) {
        if (title != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HudLabel(title, Modifier.weight(1f), accent)
                trailing?.invoke()
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.6f), Color.Transparent))))
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Hud.Cyan,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = chamferShape(5.dp)
    val color = if (enabled) accent else Hud.TextDim
    Box(
        modifier
            .clip(shape)
            // Selected must read as selected at a glance: much stronger fill and border than the rest.
            .background(color.copy(alpha = if (filled) 0.45f else if (pressed) 0.28f else 0.06f))
            .drawBehind {
                val border = if (filled) 1.5f else 1f
                drawPath(chamferPath(size, 5.dp.toPx() * 2.75f), color.copy(alpha = if (filled) 1f else 0.55f), style = Stroke(border.dp.toPx()))
            }
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = HudType.Label.copy(color = if (filled) Hud.Text else color, fontSize = 12.sp))
    }
}

@Composable
fun StatusChip(text: String, color: Color, pulse: Boolean = false) {
    val alpha = if (pulse && LocalMotion.current.animated) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value
    } else 1f
    Row(
        Modifier.background(color.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).alpha(alpha).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), style = HudType.Label.copy(color = color, fontSize = hudSmallLabel))
    }
}

/** 270-degree ring gauge with tick marks and the value in the middle. */
@Composable
fun RingGauge(fraction: Float, value: String, label: String, modifier: Modifier = Modifier, color: Color = Hud.Cyan) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "gauge")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(104.dp)) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2 + 6.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawTicks(color.copy(alpha = 0.35f), 27, 135f, 270f, size.minDimension / 2, 5.dp.toPx())
                drawArc(color.copy(alpha = 0.12f), 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                drawArc(color.copy(alpha = 0.25f), 135f, 270f * f, false, Offset(inset, inset), arcSize, style = Stroke(stroke * 2.2f))
                drawArc(color, 135f, 270f * f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            Text(value, style = HudType.Readout.copy(color = Hud.Text, fontSize = 18.sp), textAlign = TextAlign.Center)
        }
        Text(label.uppercase(), style = HudType.Label.copy(fontSize = hudSmallLabel, color = Hud.TextDim))
    }
}

private fun DrawScope.drawTicks(color: Color, count: Int, start: Float, sweep: Float, radius: Float, length: Float) {
    for (i in 0..count) {
        val a = Math.toRadians((start + sweep * i / count).toDouble())
        val c = center
        drawLine(
            color,
            Offset(c.x + (radius - length) * cos(a).toFloat(), c.y + (radius - length) * sin(a).toFloat()),
            Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat()),
            if (i % 3 == 0) 2f else 1f,
        )
    }
}

/**
 * The centerpiece: a layered reactor with a triangular core, counter-rotating segmented rings,
 * orbiting energy particles and a breathing glow. With nothing online it dims and idles at a third
 * of the speed; under reduced motion it draws one still frame. Animation only invalidates the draw
 * phase: geometry, brushes and strokes are cached per size.
 */
@Composable
fun ArcReactor(online: Int, total: Int, modifier: Modifier = Modifier) {
    val active = online > 0
    val animated = LocalMotion.current.animated
    // Speeds are whole revolutions per loop, so the wrap is seamless. Keyed on [active] because an
    // infinite transition ignores a changed duration.
    val phase: State<Float> = if (animated) {
        key(active) {
            rememberInfiniteTransition(label = "reactor").animateFloat(
                0f, 1f, infiniteRepeatable(tween(if (active) 60_000 else 180_000, easing = LinearEasing)), label = "phase",
            )
        }
    } else {
        remember { mutableFloatStateOf(0.07f) }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        Spacer(
            Modifier.fillMaxWidth().aspectRatio(1f).drawWithCache {
                val geo = ReactorGeometry(size.minDimension / 2f, Offset(size.width / 2f, size.height / 2f), active, online, total)
                onDrawBehind { drawReactor(geo, phase.value) }
            },
        )
        // Text is sized from the reactor (dp, converted so the system font scale can't grow it past
        // the core). The caption sits below the triangle's bottom edge (0.22 r), inside the capacity
        // ring (0.58 r), where no stroke crosses it.
        val r = if (maxWidth.value.isFinite()) maxWidth / 2 else 140.dp
        val density = LocalDensity.current
        val big = with(density) { (r * 0.32f).coerceIn(22.dp, 56.dp).toSp() }
        val small = with(density) { (r * 0.07f).coerceIn(7.dp, 10.dp).toSp() }
        Text("$online", style = HudType.BigReadout.copy(fontSize = big, color = if (active) Hud.Text else Hud.TextDim))
        Text(
            "OF $total ONLINE", style = HudType.Label.copy(fontSize = small, letterSpacing = small * 0.18f),
            maxLines = 1, softWrap = false, modifier = Modifier.offset(y = r * 0.37f),
        )
    }
}

/** Everything about the reactor that depends only on size and counts, built once per change. */
private class ReactorGeometry(val r: Float, val c: Offset, val active: Boolean, val online: Int, val total: Int) {
    val color = if (active) Hud.Cyan else Hud.CyanDim
    val gold = Hud.Gold
    val dim = if (active) 1f else 0.5f
    val glow = Brush.radialGradient(
        listOf(color.copy(alpha = 0.42f), color.copy(alpha = 0.12f), Color.Transparent), c, r,
    )
    val coreFill = Brush.radialGradient(
        listOf(Hud.Text.copy(alpha = 0.32f), color.copy(alpha = 0.18f), Color.Transparent), c, r * 0.46f,
    )
    val hair = Stroke((r * 0.007f).coerceAtLeast(1f))
    val thin = Stroke((r * 0.014f).coerceAtLeast(1.2f), cap = StrokeCap.Butt)
    val thinGlow = Stroke(r * 0.045f, cap = StrokeCap.Butt)
    val heavy = Stroke(r * 0.05f, cap = StrokeCap.Butt)
    val heavyGlow = Stroke(r * 0.11f, cap = StrokeCap.Butt)
    val core = trianglePath(c, r * 0.44f)
    val coreInner = trianglePath(c, r * 0.34f)
}

private fun trianglePath(c: Offset, radius: Float) = Path().apply {
    for (i in 0..2) {
        val a = Math.toRadians(-90.0 + 120.0 * i)
        val x = c.x + radius * cos(a).toFloat()
        val y = c.y + radius * sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Arc of [sweep] degrees on a circle of [radius] around the canvas center. */
private fun DrawScope.ringArc(color: Color, start: Float, sweep: Float, radius: Float, style: Stroke, alpha: Float) {
    drawArc(color, start, sweep, false, Offset(center.x - radius, center.y - radius), Size(radius * 2f, radius * 2f), alpha.coerceIn(0f, 1f), style)
}

private fun DrawScope.drawReactor(g: ReactorGeometry, p: Float) {
    val r = g.r
    val col = g.color
    val d = g.dim
    val tau = (2 * Math.PI).toFloat()
    val pulse = 0.5f + 0.5f * sin(tau * 20f * p)

    // Breathing radial glow.
    drawCircle(g.glow, r, alpha = (0.55f + 0.45f * pulse) * d)

    // Tick ring, slowest.
    rotate(360f * p) { drawTicks(col.copy(alpha = 0.45f * d), 120, 0f, 360f, r * 0.98f, r * 0.045f) }
    drawCircle(col, r * 0.92f, alpha = 0.25f * d, style = g.hair)

    // Outer segmented ring, clockwise.
    rotate(360f * 3f * p) {
        for (i in 0 until 24) {
            val start = i * 15f
            ringArc(col, start, 11f, r * 0.87f, g.thinGlow, 0.12f * d)
            ringArc(col, start, 11f, r * 0.87f, g.thin, (if (i % 6 == 0) 1f else 0.65f) * d)
        }
    }

    // Heavy gold ring, counter-clockwise.
    rotate(-360f * 5f * p) {
        for (i in 0 until 3) {
            val start = i * 120f + 10f
            ringArc(g.gold, start, 70f, r * 0.77f, g.heavyGlow, 0.18f * d)
            ringArc(g.gold, start, 70f, r * 0.77f, g.heavy, 0.9f * d)
            ringArc(g.gold, start + 84f, 14f, r * 0.77f, g.thin, 0.7f * d)
        }
    }

    // Fine dashed ring, fastest.
    rotate(360f * 8f * p) {
        for (i in 0 until 48) ringArc(col, i * 7.5f, 3.5f, r * 0.67f, g.thin, 0.5f * d)
    }

    // Capacity ring: one segment per installed app, lit when online. Static on purpose.
    if (g.total > 0) {
        val step = 360f / g.total
        val gap = if (g.total == 1) 0f else (step * 0.12f).coerceAtMost(6f)
        for (i in 0 until g.total) {
            val lit = i < g.online
            if (lit) ringArc(col, -90f + i * step + gap / 2f, step - gap, r * 0.58f, g.thinGlow, 0.25f)
            ringArc(col, -90f + i * step + gap / 2f, step - gap, r * 0.58f, g.thin, if (lit) 1f else 0.2f)
        }
    } else {
        drawCircle(col, r * 0.58f, alpha = 0.3f * d, style = g.hair)
    }

    // Energy particles with short fading trails, on two orbits in opposite directions.
    for (i in 0 until 8) {
        val outer = i % 2 == 0
        val orbit = r * if (outer) 0.93f else 0.72f
        val deg = i * 45f + 360f * p * if (outer) 6f else -4f
        val trailStart = if (outer) deg - 16f else deg
        ringArc(col, trailStart, 16f, orbit, g.thin, 0.35f * d)
        val rad = Math.toRadians(deg.toDouble())
        val pos = Offset(center.x + orbit * cos(rad).toFloat(), center.y + orbit * sin(rad).toFloat())
        drawCircle(col, r * 0.03f, pos, 0.25f * d)
        drawCircle(Hud.Text, r * 0.011f, pos, 0.95f * d)
    }

    // Triangular core: soft fill, glowing frame, inner frame and struts to the capacity ring.
    drawCircle(col, r * 0.5f, alpha = 0.5f * d, style = g.hair)
    drawPath(g.core, g.coreFill, alpha = (0.6f + 0.4f * pulse) * d)
    drawPath(g.core, col, alpha = (0.18f + 0.12f * pulse) * d, style = g.heavyGlow)
    drawPath(g.core, col, alpha = 0.95f * d, style = g.thin)
    drawPath(g.coreInner, col, alpha = 0.4f * d, style = g.hair)
    for (i in 0..2) {
        val a = Math.toRadians(-90.0 + 120.0 * i)
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        drawLine(col, Offset(center.x + r * 0.44f * ca, center.y + r * 0.44f * sa), Offset(center.x + r * 0.56f * ca, center.y + r * 0.56f * sa), g.thin.width, alpha = 0.8f * d)
    }
}

/** Thin segmented bar used for storage and memory readouts. */
@Composable
fun SegmentBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Hud.Cyan, segments: Int = 24) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "bar")
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        val lit = (f * segments).let { if (f > 0f) it.coerceAtLeast(1f) else 0f }
        for (i in 0 until segments) {
            drawRect(
                if (i < lit) color else color.copy(alpha = 0.12f),
                Offset(i * (w + gap), 0f), Size(w, size.height),
            )
        }
    }
}
