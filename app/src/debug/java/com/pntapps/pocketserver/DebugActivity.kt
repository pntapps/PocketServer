package com.pntapps.pocketserver

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.pntapps.pocketserver.linux.LinuxPaths
import com.pntapps.pocketserver.linux.Proot
import com.pntapps.pocketserver.linux.RootfsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug-only: runs `--es script` inside Ubuntu and logs output under the PSPIKE tag (used by tools/test-recipes.sh). */
class DebugActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()

    private fun log(s: String) {
        Log.i("PSPIKE", s)
        runOnUiThread { lines += s }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text(lines.joinToString("\n"), Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
        }
        val paths = LinuxPaths(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!paths.isInstalled) {
                    var last = ""
                    RootfsInstaller(paths).install { p ->
                        val s = p.toString().substringBefore("(")
                        if (s != last) { last = s; log("install: $p") }
                    }
                    log("install done")
                }
                val proot = Proot(paths)
                val script = intent.getStringExtra("script")
                    ?: "uname -a; cat /etc/os-release | head -2; id; echo PROOT_OK"
                val drop = intent.getStringExtra("drop")?.split(",").orEmpty()
                val cmd = proot.command(listOf("/bin/bash", "-c", script)).filterNot { it in drop }
                val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                pb.environment().clear()
                pb.environment().putAll(proot.hostEnv())
                intent.getStringExtra("env")?.split(",")?.filter { "=" in it }?.forEach {
                    val v = it.substringAfter("=")
                    if (v.isEmpty()) pb.environment().remove(it.substringBefore("="))
                    else pb.environment()[it.substringBefore("=")] = v
                }
                val p = pb.start()
                p.outputStream.close()
                p.inputStream.bufferedReader().forEachLine { log("| $it") }
                val code = p.waitFor()
                log("exit=$code")
            } catch (t: Throwable) {
                log("ERROR ${Log.getStackTraceString(t)}")
            }
        }
    }
}
