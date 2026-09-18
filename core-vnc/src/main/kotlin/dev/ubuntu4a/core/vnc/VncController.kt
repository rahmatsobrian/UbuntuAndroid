package dev.ubuntu4a.core.vnc

import dev.ubuntu4a.core.data.UbuntuDefaults
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.data.model.SetupProgress
import dev.ubuntu4a.core.proot.CommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

enum class VncState { STOPPED, STARTING, RUNNING, ERROR }

/**
 * Controls the TigerVNC server *inside* the distro (installed by de-*.sh
 * port) and a loopback Websockify bridge (Ubuntu app's noVNC viewer).
 */
class VncController(
    private val runner: CommandRunner,
    val port: Int = UbuntuDefaults.VNC_PORT,
    val bridgePort: Int = UbuntuDefaults.WEBSOCKIFY_PORT,
) {
    private val _state = MutableStateFlow(VncState.STOPPED)
    val state: StateFlow<VncState> = _state.asStateFlow()

    @Volatile
    var activeInstance: String? = null
        private set

    private var bridge: WebsockifyBridge? = null

    fun isPortOpen(host: String = "127.0.0.1", p: Int = port, timeoutMs: Int = 600): Boolean =
        runCatching {
            Socket().use { s -> s.connect(InetSocketAddress(host, p), timeoutMs); true }
        }.getOrDefault(false)

    fun start(
        instance: DistroInstance,
        geometry: String,
        password: String,
        user: String = instance.username,
    ): Flow<SetupProgress> = flow {
        _state.value = VncState.STARTING
        activeInstance = instance.id
        if (!isPortOpen()) {
            emit(SetupProgress.Step("Starting VNC :1"))
            val result = runner.exec(
                instance,
                "su - $user -c 'mkdir -p ~/.vnc ; export DISPLAY=:1 ; " +
                    "vncserver -kill :1 2>/dev/null ; rm -f /tmp/.X1-lock ; rm -rf /tmp/.X11-unix/X1 ; " +
                    "echo \"$password\" | vncpasswd -f > ~/.vnc/passwd ; chmod 600 ~/.vnc/passwd ; " +
                    "vncserver -localhost yes -SecurityTypes VncAuth -geometry $geometry -name Ubuntu :1'",
            )
            if (!result.success) {
                _state.value = VncState.ERROR
                emit(SetupProgress.Error("vncserver failed", result.output.take(300)))
                return@flow
            }
            var tries = 0
            while (!isPortOpen() && tries < 30) {
                kotlinx.coroutines.delay(700); tries++
            }
        } else {
            emit(SetupProgress.Step("VNC already listening"))
        }
        if (isPortOpen()) {
            startBridge()
            _state.value = VncState.RUNNING
            emit(SetupProgress.Done)
        } else {
            _state.value = VncState.ERROR
            emit(SetupProgress.Error("VNC port :$port never opened", null))
        }
    }.flowOn(Dispatchers.IO)

    fun stop(): Flow<SetupProgress> = flow {
        emit(SetupProgress.Step("Stopping VNC"))
        bridge?.stop()
        bridge = null
        activeInstance?.let { id ->
            runner.exec(DistroInstance(id, "", "", "", ""), "vncserver -kill :1 2>/dev/null ; true")
        }
        activeInstance = null
        _state.value = VncState.STOPPED
        emit(SetupProgress.Done)
    }.flowOn(Dispatchers.IO)

    private fun startBridge() {
        if (bridge?.isRunning == true) return
        bridge = WebsockifyBridge(bridgePort, "127.0.0.1", port).also { it.start() }
    }

    fun bridgeUrl(): String =
        "ws://127.0.0.1:$bridgePort/websockify"
}
