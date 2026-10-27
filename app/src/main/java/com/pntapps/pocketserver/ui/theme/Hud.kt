package com.pntapps.pocketserver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pntapps.pocketserver.R

/** Arc-reactor palette: one cool primary for data, one warm accent for action, red only for alerts. */
object Hud {
    val Void = Color(0xFF03060C)
    val Deep = Color(0xFF07101C)
    val Panel = Color(0xCC0A1626)
    val PanelSolid = Color(0xFF0A1626)
    val Line = Color(0xFF1B3A55)
    val Cyan = Color(0xFF3FE0FF)
    val CyanDim = Color(0xFF1E7F99)
    val Gold = Color(0xFFFFB547)
    val Red = Color(0xFFFF4D5E)
    val Green = Color(0xFF4DFFB0)
    val Text = Color(0xFFD8F6FF)
    val TextDim = Color(0xFF7FA3B8)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun orbitron(weight: Int) =
    Font(R.font.orbitron, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Display = FontFamily(orbitron(500), orbitron(700), orbitron(900))
val Body = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
)
val Mono = FontFamily(Font(R.font.sharetech_mono))

object HudType {
    val Title = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 3.sp, color = Hud.Text)
    val Label = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 2.5.sp, color = Hud.Cyan)
    val Body = TextStyle(fontFamily = com.pntapps.pocketserver.ui.theme.Body, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Hud.Text)
    val BodyDim = Body.copy(color = Hud.TextDim, fontSize = 14.sp)
    val Readout = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = Hud.Cyan, letterSpacing = 0.5.sp)
    // Rajdhani, not Orbitron: Orbitron's slashed zero reads as a "no" symbol at large sizes.
    val BigReadout = TextStyle(fontFamily = com.pntapps.pocketserver.ui.theme.Body, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = Hud.Text)
}

@Composable
fun PocketServerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Hud.Cyan, onPrimary = Hud.Void, secondary = Hud.Gold, error = Hud.Red,
            background = Hud.Void, surface = Hud.PanelSolid, onSurface = Hud.Text, onBackground = Hud.Text,
        ),
        typography = Typography(bodyLarge = HudType.Body, bodyMedium = HudType.Body, labelLarge = HudType.Label),
        content = content,
    )
}
