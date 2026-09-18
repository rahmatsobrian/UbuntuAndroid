package dev.ubuntu4a.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import dev.ubuntu4a.core.data.UbuntuDefaults

class UbuntuApp : Application() {

    lateinit var services: Services
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        services = Services(this)
        createChannel()
    }

    // Any crash from here on out gets logged (tag "UbuntuFatal") before the process dies,
    // instead of Android just printing a bare "Process X has died" with no Kotlin stack
    // trace attached. If a force-close still happens after this build, `adb logcat -s
    // UbuntuFatal` right after it happens will show exactly which line threw — that's the
    // fastest way to pin down anything this pass didn't catch.
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { Log.e("UbuntuFatal", "Uncaught on ${thread.name}", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(
                    UbuntuDefaults.NOTIFICATION_CHANNEL,
                    "Ubuntu session",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps Ubuntu containers and the VNC bridge alive"
                    setShowBadge(false)
                },
            )
        }
    }
}
