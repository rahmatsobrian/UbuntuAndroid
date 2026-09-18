package dev.ubuntu4a.core.data

import android.content.Context
import java.io.File

object InstancePaths {
    fun containerDir(context: Context, instanceId: String): File =
        File(File(context.filesDir, "containers"), instanceId).apply { mkdirs() }

    fun rootfs(context: Context, instanceId: String): File =
        File(containerDir(context, instanceId), "rootfs")

    fun downloadDir(context: Context, instanceId: String): File =
        File(containerDir(context, instanceId), "downloads").apply { mkdirs() }

    fun backupDir(context: Context): File =
        File(context.filesDir, "backups").apply { mkdirs() }

    fun logFile(context: Context, instanceId: String): File =
        File(containerDir(context, instanceId), "session.log")

    fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile && !it.isDirectory }.sumOf { it.length() }
}

object UbuntuDefaults {
    const val SESSION_NOTIFICATION_ID = 42
    const val NOTIFICATION_CHANNEL = "ubuntu_session"
    const val VNC_PORT = 5901
    const val WEBSOCKIFY_PORT = 6080
    const val PULSE_PORT = 4713
    val ENV_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
}
