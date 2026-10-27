package com.pntapps.pocketserver.ui.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * How much the HUD animates. Animations only ever run while the app is on screen (Compose pauses
 * its frame clock when the activity stops), so this is about taste and accessibility, not idle drain.
 * [Reduced] draws still frames: no infinite loops, entrance effects skipped.
 */
enum class Motion { Full, Reduced }

val LocalMotion = compositionLocalOf { Motion.Full }

object MotionPrefs {
    private const val FILE = "ui"
    private const val KEY = "reduced_motion"

    /** User's choice; observed by [ProvideMotion]. */
    var reducedByUser by mutableStateOf(false)
        private set

    fun load(context: Context) {
        reducedByUser = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun setReduced(context: Context, reduced: Boolean) {
        reducedByUser = reduced
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, reduced).apply()
    }

    /** Android's "Remove animations" accessibility setting sets the animator scale to 0. */
    fun systemAnimationsOff(context: Context) =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** [deviceWarm]: the thermal guard says the device is warm, so the HUD stops animating to shed GPU heat. */
@Composable
fun ProvideMotion(deviceWarm: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val motion = if (deviceWarm || MotionPrefs.reducedByUser || MotionPrefs.systemAnimationsOff(context)) Motion.Reduced else Motion.Full
    CompositionLocalProvider(LocalMotion provides motion, content = content)
}

/** True when looping/decorative animation should run. */
val Motion.animated get() = this == Motion.Full
