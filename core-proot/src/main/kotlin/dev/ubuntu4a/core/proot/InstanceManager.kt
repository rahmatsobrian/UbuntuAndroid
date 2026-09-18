package dev.ubuntu4a.core.proot

import android.content.Context
import dev.ubuntu4a.core.data.InstancePaths
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.data.model.SetupProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/** Backup / restore / reset of a container rootfs (tar streamed inside proot). */
class InstanceManager(
    private val context: Context,
    private val runner: CommandRunner,
) {

    fun backup(instance: DistroInstance, name: String): Flow<SetupProgress> = flow {
        emit(SetupProgress.Step("Backing up ${instance.id}"))
        val rootfs = InstancePaths.rootfs(context, instance.id)
        val tarName = safe(name) + ".tar.gz"
        val cmd = "tar -C / -czf /root/$tarName --exclude=./proc --exclude=./sys " +
            "--exclude=./dev --exclude=./tmp/* --exclude=./sdcard " +
            "--exclude=./data/data/${context.packageName} ."
        val result = runner.exec(instance, cmd, timeoutSec = 3600)
        val staged = File(rootfs, "root/$tarName")
        val out = File(InstancePaths.backupDir(context), tarName)
        if (result.success && staged.isFile) {
            if (out.exists()) out.delete()
            staged.renameTo(out)
            emit(SetupProgress.Done)
        } else {
            emit(SetupProgress.Error("Backup failed", result.output.take(300)))
        }
    }.flowOn(Dispatchers.IO)

    fun restore(instance: DistroInstance, backupFile: File): Flow<SetupProgress> = flow {
        emit(SetupProgress.Step("Restoring over ${instance.id}"))
        val rootfs = InstancePaths.rootfs(context, instance.id)
        val staged = File(rootfs, "root/" + backupFile.name)
        backupFile.copyTo(staged, overwrite = true)
        val cmd = "tar -C / -xzf /root/${backupFile.name} --warning=no-timestamp"
        val result = runner.exec(instance, cmd, timeoutSec = 3600)
        staged.delete()
        emit(if (result.success) SetupProgress.Done else SetupProgress.Error("Restore failed", result.output.take(300)))
    }.flowOn(Dispatchers.IO)

    suspend fun reset(instance: DistroInstance): Boolean = runCatching {
        InstancePaths.rootfs(context, instance.id).deleteRecursively()
        true
    }.getOrDefault(false)

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
