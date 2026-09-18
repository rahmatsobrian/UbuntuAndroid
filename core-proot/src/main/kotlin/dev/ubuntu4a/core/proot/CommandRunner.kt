package dev.ubuntu4a.core.proot

import android.content.Context
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.data.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class ExecResult(val exitCode: Int, val output: String) {
    val success: Boolean get() = exitCode == 0
}

/**
 * Non-interactive proot command execution (apt, vncserver, useradd …).
 * Everything funnels through the same ProotCommandBuilder the interactive
 * shell uses, mirroring `bash .ubuntu <command>` from the reference script.
 */
class CommandRunner(
    private val context: Context,
    private val binaries: BinaryProvider,
    private val settings: SettingsRepository,
) {

    suspend fun buildArgs(
        instance: DistroInstance,
        command: String,
        asRoot: Boolean = true,
        cwd: String = "/root",
    ): List<String> {
        val binds = BindsRepository.load(context, instance)
        val kernel = settings.first().kernelReleaseSpoof
        return ProotCommandBuilder(context, binaries).build(
            instance = instance,
            binds = binds,
            kernelRelease = kernel,
            asRoot = asRoot,
            command = command,
            cwd = cwd,
        )
    }

    suspend fun exec(
        instance: DistroInstance,
        command: String,
        asRoot: Boolean = true,
        timeoutSec: Long = 0,
    ): ExecResult = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(buildArgs(instance, command, asRoot)).redirectErrorStream(true)
        val proc = pb.start()
        val sb = StringBuilder()
        val readJob = launch {
            proc.inputStream.bufferedReader().forEachLine { line -> sb.appendLine(line) }
        }
        val code = if (timeoutSec > 0) {
            kotlinx.coroutines.withTimeoutOrNull(timeoutSec * 1000) {
                readJob.join()
                proc.waitFor()
            } ?: run {
                proc.destroyForcibly()
                sb.appendLine("[timeout after ${timeoutSec}s]")
                124
            }
        } else {
            readJob.join()
            proc.waitFor()
        }
        ExecResult(code, sb.toString())
    }

    suspend fun execStreaming(
        instance: DistroInstance,
        command: String,
        asRoot: Boolean = true,
        onLine: suspend (String) -> Unit,
    ): ExecResult = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(buildArgs(instance, command, asRoot))
            .redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) onLine(line)
        }
        val code = proc.waitFor()
        ExecResult(code, "")
    }
}

object BindsRepository {
    fun file(context: Context, instance: DistroInstance) =
        java.io.File(dev.ubuntu4a.core.data.InstancePaths.containerDir(context, instance.id), "binds.list")

    suspend fun load(context: Context, instance: DistroInstance): List<dev.ubuntu4a.core.data.model.BindEntry> {
        val f = file(context, instance)
        return if (f.isFile) f.readLines().mapNotNull { dev.ubuntu4a.core.data.model.BindEntry.parse(it) }
        else emptyList()
    }

    suspend fun save(context: Context, instance: DistroInstance, binds: List<dev.ubuntu4a.core.data.model.BindEntry>) {
        file(context, instance).writeText(binds.joinToString("\n") { it.toLine() })
    }
}
