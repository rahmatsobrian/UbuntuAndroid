package dev.ubuntu4a.core.proot

import android.content.Context
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.data.repo.SettingsRepository
import dev.ubuntu4a.core.proot.BindsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one interactive PTY session per distro instance. Referenced by the
 * foreground service so sessions survive the Activity being backgrounded.
 */
class SessionManager(
    private val context: Context,
    private val binaries: BinaryProvider,
    private val settings: SettingsRepository,
) {
    private val sessions = ConcurrentHashMap<String, ProotProcess>()
    private val _activeIds = MutableStateFlow<Set<String>>(emptySet())
    val activeIds: StateFlow<Set<String>> = _activeIds.asStateFlow()

    fun isRunning(instanceId: String): Boolean = sessions[instanceId]?.isAlive == true

    fun openTerminal(instance: DistroInstance, cols: Int, rows: Int, asRoot: Boolean): ProotProcess {
        close(instance.id)
        val binds = kotlinx.coroutines.runBlocking { BindsRepository.load(context, instance) }
        val kernel = kotlinx.coroutines.runBlocking { settings.first() }.kernelReleaseSpoof
        val argv = ProotCommandBuilder(context, binaries).build(
            instance = instance,
            binds = binds,
            kernelRelease = kernel,
            asRoot = asRoot,
            command = null,
        )
        val proc = ProotLauncher.start(argv, cols, rows)
        sessions[instance.id] = proc
        publish()
        return proc
    }

    fun get(instanceId: String): ProotProcess? = sessions[instanceId]?.takeIf { it.isAlive }

    fun close(instanceId: String) {
        val proc = sessions.remove(instanceId)
        publish()
        // close() is called from TerminalScreen's DisposableEffect.onDispose, which runs on
        // the main thread. ProotProcess.destroy() sends SIGTERM, blocks for 200ms, then
        // SIGKILL — that used to happen right there on the main thread, so leaving the
        // terminal screen briefly froze the UI and, on a slow/loaded device, risked an ANR
        // right as the user landed back on the dashboard. Tearing the process down is fired
        // off the main thread instead; the map removal above (and publish()) still happen
        // synchronously so isRunning/activeIds reflect the change immediately.
        if (proc != null) {
            Thread { runCatching { proc.destroy() } }.start()
        }
    }

    fun closeAll() {
        val procs = sessions.values.toList()
        sessions.clear()
        publish()
        procs.forEach { p -> Thread { runCatching { p.destroy() } }.start() }
    }

    private fun publish() {
        _activeIds.value = sessions.filterValues { it.isAlive }.keys.toSet()
    }
}
