package com.pntapps.pocketserver.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.pntapps.pocketserver.MainActivity
import com.pntapps.pocketserver.R
import com.pntapps.pocketserver.catalog.Catalog

/**
 * The foreground-service notification, drawn in the HUD style: deep navy (colorized, which Android
 * allows for foreground services), Orbitron status line, one row per app with its address and a live
 * uptime counter. The counters are Chronometer widgets ticking inside the system UI, so the app
 * itself never wakes up to update them; content is rebuilt only on state changes and the power
 * policy's once-a-minute tick.
 */
internal object ServerNotifications {
    private const val CHANNEL = "servers"
    private const val NAVY = 0xFF07101C.toInt()
    private const val GREEN = 0xFF4DFFB0.toInt()
    private const val GOLD = 0xFFFFB547.toInt()
    private const val RED = 0xFFFF4D5E.toInt()
    private const val CYAN = 0xFF3FE0FF.toInt()
    private const val MAX_ROWS = 5

    enum class Mode { Running, Working, Waiting }

    fun build(context: Context, manager: AppManager, mode: Mode): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Running servers", NotificationManager.IMPORTANCE_LOW))

        val content = when (mode) {
            Mode.Running -> running(context, manager)
            Mode.Working -> working(manager)
            Mode.Waiting -> waiting(manager)
        }
        val small = views(context, R.layout.notif_hud_small, content)
        val big = views(context, R.layout.notif_hud_big, content).apply {
            // The expanded area is capped (~252 dp): five rows plus a summary keep the stats visible.
            val shown = if (content.rows.size > MAX_ROWS) content.rows.take(MAX_ROWS - 1) else content.rows
            val hidden = content.rows.size - shown.size
            val rows = if (hidden > 0) shown + Row("+ $hidden more", "", null) else shown
            rows.forEach { row ->
                addView(R.id.rows, RemoteViews(context.packageName, R.layout.notif_hud_row).apply {
                    setTextViewText(R.id.name, row.name.uppercase())
                    setTextViewText(R.id.address, row.address)
                    // With one app the header clock already shows its uptime.
                    if (row.since != null && content.rows.size > 1) setChronometer(R.id.uptime, elapsedBase(row.since), null, true)
                    else setViewVisibility(R.id.uptime, View.GONE)
                    if (row.address.isEmpty()) setViewVisibility(R.id.address, View.GONE)
                    if (row.starting) setTextColor(R.id.marker, GOLD)
                })
            }
            setTextViewText(R.id.stats, stats(context, manager))
            // The rows already say which apps run and where: the summary line would only repeat them.
            if (content.rows.isNotEmpty()) setViewVisibility(R.id.line, View.GONE)
        }

        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(content.title)
            .setContentText(content.line)
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setColor(NAVY)
            .setColorized(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .setPublicVersion(
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(content.title)
                    .setColor(NAVY)
                    .build()
            )
        content.openUrl?.let { (name, url) ->
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Short labels: long ones are cut off on phones. Tapping the notification opens the dashboard.
            builder.addAction(Notification.Action.Builder(null, "Open", PendingIntent.getActivity(context, 2, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        if (mode != Mode.Working) builder.addAction(Notification.Action.Builder(null, "Stop all", ServerService.stopAllIntent(context)).build())
        return builder.build()
    }

    private class Row(val name: String, val address: String, val since: Long?, val starting: Boolean = false)

    private class Content(
        val title: String,
        val line: String,
        val dotColor: Int,
        val since: Long? = null,
        val progress: Boolean = false,
        val rows: List<Row> = emptyList(),
        val openUrl: Pair<String, String>? = null,
    )

    private fun running(context: Context, manager: AppManager): Content {
        val states = manager.apps.value
        val active = Catalog.apps.filter { states[it.id]?.active == true }
        val ip = manager.lanAddresses().firstOrNull()
        val heat = manager.thermal.heat.value
        val rows = active.map { app ->
            val phase = states[app.id]?.phase
            val address = when {
                app.localOnly -> if (states[app.id]?.lan == true) "wi-fi :${app.port}" else "this device :${app.port}"
                ip != null -> "$ip:${app.port}"
                else -> "127.0.0.1:${app.localPort ?: app.port}"
            }
            Row(app.name, address, (phase as? AppPhase.Running)?.since, starting = phase is AppPhase.Starting)
        }
        val since = rows.mapNotNull { it.since }.minOrNull()
        val title = when {
            heat >= Heat.Hot -> "THERMAL LOCK · COOLING"
            active.size == 1 -> "1 SYSTEM ONLINE"
            else -> "${active.size} SYSTEMS ONLINE"
        }
        val line = when {
            heat == Heat.Warm -> "Cooling mode (device warm) · " + active.joinToString(" · ") { it.name }
            ip == null -> active.joinToString(" · ") { it.name } + " · no Wi-Fi"
            else -> active.joinToString(" · ") { it.name } + " · $ip"
        }
        val web = active.singleOrNull()?.takeIf { !it.localOnly && states[it.id]?.phase is AppPhase.Running }
        return Content(
            title, line,
            dotColor = when { heat >= Heat.Hot -> RED; heat == Heat.Warm -> GOLD; else -> GREEN },
            since = since, rows = rows,
            openUrl = web?.let { it.name to it.localUrl },
        )
    }

    private fun working(manager: AppManager): Content {
        val installing = Catalog.apps.firstNotNullOfOrNull { app ->
            (manager.apps.value[app.id]?.phase as? AppPhase.Installing)?.let { app.name to it.step }
        }
        val system = manager.system.value as? SystemState.Installing
        return when {
            installing != null -> Content("INSTALLING ${installing.first.uppercase()}", installing.second, GOLD, progress = true)
            system != null -> Content("SETTING UP UBUNTU", "First-time setup · keeps going if you leave the app", GOLD, progress = true)
            else -> Content("BACKUP IN PROGRESS", "Keeps going if you leave the app", GOLD, progress = true)
        }
    }

    private fun waiting(manager: AppManager): Content {
        val n = manager.power.pausedCount
        return Content(
            "PAUSED · WAITING FOR CHARGER",
            if (n == 1) "1 app resumes when you plug in" else "$n apps resume when you plug in",
            CYAN,
        )
    }

    private fun views(context: Context, layout: Int, c: Content) = RemoteViews(context.packageName, layout).apply {
        setTextViewText(R.id.title, c.title)
        setTextViewText(R.id.line, c.line)
        setTextColor(R.id.dot, c.dotColor)
        if (c.since != null) setChronometer(R.id.clock, elapsedBase(c.since), null, true)
        else setViewVisibility(R.id.clock, View.GONE)
        setViewVisibility(R.id.progress, if (c.progress) View.VISIBLE else View.GONE)
    }

    /** "0,1,2,3" -> "0-3", "0,1,2,3,6" -> "0-3,6". */
    internal fun coreRanges(list: String): String {
        val cores = list.split(',').mapNotNull { it.trim().toIntOrNull() }.sorted()
        if (cores.isEmpty()) return list
        val out = mutableListOf<String>()
        var start = cores[0]; var prev = start
        for (c in cores.drop(1) + Int.MIN_VALUE) {
            if (c == prev + 1) { prev = c; continue }
            out += if (start == prev) "$start" else "$start-$prev"
            start = c; prev = c
        }
        return out.joinToString(",")
    }

    /** "BATT 64% CHG · 33 °C · CORES 0-3 · AUTO-STOP IN 42 MIN" */
    private fun stats(context: Context, manager: AppManager): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else null
        }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val parts = buildList {
            level?.let { add("BATT $it%" + if (plugged) " CHG" else "") }
            add("${manager.thermal.reading().batteryC.toInt()} °C")
            ThermalGuard.efficiencyCores?.let { add("CORES ${coreRanges(it)}") }
            manager.powerStatus.value?.let { add(it.uppercase()) }
        }
        // Non-breaking inside each part and plain gaps between them: a wrapped line never splits
        // "AUTO-STOP IN 42 MIN" or ends with a dangling separator.
        return parts.joinToString("   ") { it.replace(' ', '\u00A0') }
    }

    /** Chronometer counts from an elapsedRealtime base; app phases record wall-clock start times. */
    private fun elapsedBase(wallClockStart: Long) =
        SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wallClockStart).coerceAtLeast(0)

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        // Brings the existing screen forward instead of stacking a second copy of the app.
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
