package com.pntapps.pocketserver.server

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/**
 * Exists only while at least one app is running, or while apps are paused waiting for the charger
 * (home-server mode). Running, it keeps the CPU and Wi-Fi awake so other devices can reach the
 * servers; waiting, it holds no locks at all and only listens for the charger. The power policy
 * is attached for exactly the lifetime of this service, so with everything stopped nothing runs.
 */
class ServerService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Also set here: a start that raced with the previous instance's onDestroy lands after it.
        started = true
        AppManager.get(this).power.attach()
        // Only while the service exists (nothing listens with everything stopped).
        registerReceiver(cardWatcher, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }, Context.RECEIVER_NOT_EXPORTED) // system broadcasts still arrive; no other app can send these
    }

    /**
     * Stops every server when the SD card holding /shared folders goes away (or is about to: EJECT
     * comes first), before apps see half their files vanish or write into the empty internal copy.
     */
    private val cardWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val manager = AppManager.get(context)
            val storage = manager.paths.storage
            if (storage.config.value.onSd.isEmpty()) return
            val volume = intent.data?.path
            val ours = storage.sharedRoots().drop(1).any { volume != null && it.path.startsWith(volume) }
            if (!ours && storage.problem() == null) return
            if (manager.runningCount == 0) return
            manager.power.stopEverything(
                "Stopped: SD card removed", "Servers stopped: SD card removed",
                "Your media folders on the SD card are no longer available. Insert the card again to start the apps.",
                alert = true,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = AppManager.get(this)
        if (intent?.action == ACTION_STOP_ALL) {
            manager.stopAll()
            // Delivered to a service that had nothing to do (e.g. a stale notification button): end it.
            if (manager.runningCount == 0 && !manager.busy && !manager.power.waitingForCharger) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // The live count, not the intent's: queued updates (stop then start) can arrive out of date.
        val running = manager.runningCount
        val working = running == 0 && manager.busy
        val waiting = running == 0 && !working && manager.power.waitingForCharger
        val notification = ServerNotifications.build(
            this, manager,
            when {
                working -> ServerNotifications.Mode.Working
                waiting -> ServerNotifications.Mode.Waiting
                else -> ServerNotifications.Mode.Running
            },
        )
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        when {
            running > 0 -> acquireLocks()
            // Installs and backups need the CPU, not Wi-Fi for other devices.
            working -> acquireLocks(wifi = false)
            // Nothing runs while waiting: let the CPU and Wi-Fi sleep until the charger is back.
            waiting -> releaseLocks()
            // Only if no newer start is queued, which would otherwise be dropped with this one.
            else -> stopSelfResult(startId)
        }
        // Not sticky: if Android kills us, servers are gone too, and we never restart them unasked.
        return START_NOT_STICKY
    }

    // No timeout on purpose: the lock lives exactly as long as a server/job and is released with it.
    private fun acquireLocks(wifi: Boolean = true) {
        if (!wifi) { wifiLock?.release(); wifiLock = null }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketServer:servers")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifi && wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PocketServer:servers")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.release(); wakeLock = null
        wifiLock?.release(); wifiLock = null
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(cardWatcher) }
        AppManager.get(this).power.detach()
        releaseLocks()
        started = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_RUNNING = "running"
        private const val ACTION_STOP_ALL = "stop_all"

        fun stopAllIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 1, Intent(context, ServerService::class.java).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_IMMUTABLE,
        )

        /** True while the service exists (from the first start until onDestroy). */
        @Volatile private var started = false

        /**
         * Starts, updates or stops the service to match the number of running apps. With none running
         * it stays only if the power policy is waiting for the charger to resume paused apps.
         */
        fun sync(context: Context, running: Int) {
            val manager = AppManager.get(context)
            val wanted = running > 0 || manager.busy || manager.power.waitingForCharger
            if (!wanted && !started) return
            val intent = Intent(context, ServerService::class.java).putExtra(EXTRA_RUNNING, running)
            // Android 12+ can refuse a foreground start from the background; never crash on it
            // (the next state change, typically with the app visible, tries again).
            fun startForeground() = runCatching { context.startForegroundService(intent) }.onFailure { started = false }
            if (!started) {
                started = true
                startForeground()
                return
            }
            // Already running (and foreground): a plain start just delivers the update. That is also
            // allowed from the background, e.g. when the charger brings paused apps back.
            runCatching { context.startService(intent) }.onFailure {
                if (wanted) { started = true; startForeground() }
            }
        }
    }
}
