package dev.ubuntu4a.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ubuntu4a.core.data.UbuntuDefaults
import dev.ubuntu4a.core.vnc.VncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistent session service: keeps terminal proot processes and the websockify
 * bridge alive while the UI is backgrounded (Android 13+ notification,
 * Android 14+ explicit FGS type with specialUse subtype).
 */
class UbuntuSessionService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    // Tracks the single "watch activeIds and stop when empty" collector. onStartCommand can
    // fire once per UbuntuSessionService.start() call — i.e. every single time a terminal or
    // desktop is opened, not just the first — and it used to launch a brand-new collector
    // each time without ever cancelling the previous one. After a few sessions there were
    // several of these running at once, all reacting independently to the same StateFlow, so
    // e.g. closing one terminal could trigger several concurrent stopEverything()+stopSelf()
    // calls stepping on each other. One watcher, replaced (not stacked) on every restart.
    private var watcherJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(build(0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }
        val services = (application as UbuntuApp).services
        watcherJob?.cancel()
        watcherJob = scope.launch {
            services.sessions.activeIds.collect { ids ->
                // Defensive: nothing in here should be able to take the whole app process
                // down. Whatever's inside (notification post, vnc/audio teardown) failing
                // should, at worst, leave a stale notification behind — not crash a screen
                // the user has already safely navigated away from.
                runCatching {
                    val vncRunning = services.vnc.state.value == VncState.RUNNING
                    if (ids.isEmpty() && !vncRunning) {
                        stopEverything()
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(UbuntuDefaults.SESSION_NOTIFICATION_ID, build(ids.size))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun stopEverything() {
        val services = (application as UbuntuApp).services
        runCatching { services.sessions.closeAll() }
        runCatching { services.audio.stop() }
        scope.launch { runCatching { services.vnc.stop().collect { } } }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                UbuntuDefaults.SESSION_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(UbuntuDefaults.SESSION_NOTIFICATION_ID, notification)
        }
    }

    private fun build(sessions: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, UbuntuSessionService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, UbuntuDefaults.NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ubuntu)
            .setContentTitle("Ubuntu session running")
            .setContentText("$sessions container session(s) active")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .addAction(0, "Stop all", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_ALL = "dev.ubuntu4a.action.STOP_ALL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, UbuntuSessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UbuntuSessionService::class.java))
        }
    }
}

