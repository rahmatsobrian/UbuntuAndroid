package dev.ubuntu4a.core.proot

import android.content.Context
import dev.ubuntu4a.core.data.InstancePaths
import dev.ubuntu4a.core.data.UbuntuDefaults
import dev.ubuntu4a.core.data.model.DistroInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENTAL root path: when the device is rooted, skip proot's ptrace and
 * use a real chroot with bind mounts, exactly the mounts proot emulates.
 * Falls back to [ProotCommandBuilder] whenever anything fails.
 */
object NativeChroot {

    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS) && out == "0"
        }.getOrDefault(false)
    }

    fun buildCommand(context: Context, instance: DistroInstance, command: String?): List<String> {
        val rootfs = InstancePaths.rootfs(context, instance.id).absolutePath
        val inner = buildString {
            append("mount -o bind /dev $rootfs/dev; ")
            append("mount -t proc proc $rootfs/proc; ")
            append("mount -o bind /sys $rootfs/sys; ")
            append("chroot $rootfs /usr/bin/env -i HOME=/root PATH=${UbuntuDefaults.ENV_PATH} " +
                "TERM=xterm-256color LANG=C.UTF-8 PULSE_SERVER=127.0.0.1 /bin/bash")
            if (command != null) append(" -lc '${command.replace("'", "'\\''")}'")
            else append(" --login")
        }
        return listOf("su", "-c", inner)
    }
}
