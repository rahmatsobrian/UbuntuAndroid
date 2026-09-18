package dev.ubuntu4a.core.proot.setup

import android.content.Context
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.proot.BinaryProvider
import dev.ubuntu4a.core.proot.CommandRunner
import java.io.File
import java.util.concurrent.TimeUnit

enum class AudioMode { NONE, HOST_PULSE_AAUDIO, CONTAINER_PULSE }

/**
 * Port of the `pulseaudio --start --load=module-aaudio-sink …` line from the
 * generated `.ubuntu` script. Preferred mode is a host-side PulseAudio built
 * against NDK's AAudio backend (bundled as libubuntu-pulseaudio.so); the
 * fallback runs the container's own pulseaudio with a TCP protocol module.
 */
class AudioBridge(
    private val context: Context,
    private val binaries: BinaryProvider,
    private val runner: CommandRunner,
) {
    @Volatile
    var mode: AudioMode = AudioMode.NONE
        private set

    private var hostProcess: Process? = null

    fun hostBinary(): File? = binaries.pulseAudioBinary()

    suspend fun start(instance: DistroInstance): Boolean {
        stop()
        hostBinary()?.let { pa ->
            val proc = runCatching {
                ProcessBuilder(
                    pa.absolutePath,
                    "--exit-idle-time=-1",
                    "--disallow-exit",
                    "--load=module-native-protocol-tcp",
                    "--load=module-aaudio-sink",
                ).apply {
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["XDG_RUNTIME_DIR"] = context.cacheDir.absolutePath
                    redirectErrorStream(true)
                    redirectOutput(File(context.cacheDir, "pulseaudio.log"))
                }.start()
            }.getOrNull()
            if (proc != null && proc.waitFor(2, TimeUnit.SECONDS).not() && proc.isAlive) {
                hostProcess = proc
                mode = AudioMode.HOST_PULSE_AAUDIO
                return true
            }
        }
        val result = runner.exec(
            instance,
            "pulseaudio --start --exit-idle-time=-1 --disallow-exit " +
                "--load='module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1'",
        )
        mode = if (result.success) AudioMode.CONTAINER_PULSE else AudioMode.NONE
        return result.success
    }

    fun stop() {
        hostProcess?.destroy()
        hostProcess = null
        mode = AudioMode.NONE
    }
}
