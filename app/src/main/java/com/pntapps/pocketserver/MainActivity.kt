package com.pntapps.pocketserver

import com.pntapps.pocketserver.server.Heat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.ui.PocketServerApp
import com.pntapps.pocketserver.ui.motion.MotionPrefs
import com.pntapps.pocketserver.ui.motion.ProvideMotion
import com.pntapps.pocketserver.ui.theme.PocketServerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(0), SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        // The "apps running" notification is how users see (and stop) anything using battery.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val manager = AppManager.get(this)
        MotionPrefs.load(this)
        // The HUD is a control panel, not a game: 60 Hz halves GPU work (and heat) on 120 Hz screens.
        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
        setContent {
            val heat by manager.thermal.heat.collectAsState()
            PocketServerTheme { ProvideMotion(deviceWarm = heat >= Heat.Warm) { PocketServerApp(manager) } }
        }
    }
}
