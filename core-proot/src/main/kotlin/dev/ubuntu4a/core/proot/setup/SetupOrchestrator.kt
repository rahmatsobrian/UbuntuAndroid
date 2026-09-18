package dev.ubuntu4a.core.proot.setup

import android.content.Context
import android.net.Uri
import dev.ubuntu4a.core.data.InstancePaths
import dev.ubuntu4a.core.data.catalog.UbuntuCatalog
import dev.ubuntu4a.core.data.model.DesktopEnv
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.data.model.SetupProgress
import dev.ubuntu4a.core.data.repo.SettingsRepository
import dev.ubuntu4a.core.proot.AptRunner
import dev.ubuntu4a.core.proot.BinaryProvider
import dev.ubuntu4a.core.proot.CommandRunner
import dev.ubuntu4a.core.rootfs.DownloadState
import dev.ubuntu4a.core.rootfs.ExtractState
import dev.ubuntu4a.core.rootfs.RootfsDownloader
import dev.ubuntu4a.core.rootfs.RootfsExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File

data class InstallRequest(
    val codeName: String,
    val version: String,
    val arch: String,
    val username: String,
    val password: String,
    val desktopEnv: DesktopEnv,
    val localArchive: Uri? = null,
)

/**
 * Everything `ubuntu.sh` did interactively — as a reactive pipeline:
 * download (or import local tar) → extract → provision files → create users →
 * apt bootstrap → optional desktop environment → VNC scripts → audio.
 */
class SetupOrchestrator(
    private val context: Context,
    private val binaries: BinaryProvider,
    private val settings: SettingsRepository,
    private val downloader: RootfsDownloader,
    private val extractor: RootfsExtractor,
    private val runner: CommandRunner,
    private val audio: AudioBridge,
) {

    fun install(req: InstallRequest, instance: DistroInstance): Flow<SetupProgress> = channelFlow {
        // channelFlow (not the plain `flow{}` builder) is required here: several steps below
        // (execStreaming's line callback, called from inside a withContext(Dispatchers.IO) in
        // CommandRunner) emit from a different coroutine context than the one that started
        // collecting. `flow{}`'s `emit` forbids that ("Flow invariant is violated") and crashes
        // the whole app the instant apt output starts streaming; `send` on a channelFlow does not.
        try {
            if (!binaries.isProotAvailable) {
                send(SetupProgress.Error("proot binary is not bundled in this APK", "libubuntu-proot.so missing"))
                return@channelFlow
            }
            val appSettings = settings.first()
            val rootfs = InstancePaths.rootfs(context, instance.id)
            val downloadDir = InstancePaths.downloadDir(context, instance.id)
            val archive = File(downloadDir, "ubuntu-${req.codeName}-${req.arch}-rootfs")

            if (!archive.exists() || archive.length() == 0L) {
                send(SetupProgress.Step("Downloading rootfs"))
                var fatal: String? = null
                if (req.localArchive != null) {
                    importLocal(req.localArchive, archive, ::send)
                } else {
                    val (url, sha) = UbuntuCatalog.resolve(
                        appSettings.rootfsSource,
                        appSettings.rootfsMirror,
                        req.codeName,
                        req.arch,
                    )
                    send(SetupProgress.Log("GET $url"))
                    downloader.download(url, archive, sha).collect { st ->
                        when (st) {
                            is DownloadState.Progress -> send(
                                SetupProgress.Percent("Downloading rootfs", st.fraction(), st.transferred, st.total),
                            )
                            is DownloadState.Resuming -> send(SetupProgress.Log("Resuming at ${st.from} bytes"))
                            is DownloadState.Checksum -> send(
                                SetupProgress.Log(
                                    if (st.expected == null) "sha256 ${st.actual.take(12)}… (no upstream checksum to compare)"
                                    else if (st.verified) "sha256 verified ✓" else "sha256 MISMATCH",
                                ),
                            )
                            is DownloadState.Done -> send(SetupProgress.Step("Download complete", archive.name))
                            is DownloadState.Failed -> fatal = st.message
                        }
                    }
                }
                if (fatal != null) {
                    send(SetupProgress.Error("Download failed", fatal))
                    return@channelFlow
                }
            } else {
                send(SetupProgress.Step("Reusing cached rootfs archive", archive.name))
            }

            if (!rootfs.exists() || File(rootfs, "etc").exists().not()) {
                send(SetupProgress.Step("Extracting rootfs"))
                var done = false
                extractor.extract(archive, rootfs).collect { st ->
                    when (st) {
                        is ExtractState.Progress -> send(SetupProgress.Log("extract ${st.entries} files … ${st.current}"))
                        is ExtractState.Done -> {
                            send(SetupProgress.Step("Extracted ${st.entries} files"))
                            done = true
                        }
                        is ExtractState.Failed -> {
                            send(SetupProgress.Error("Extraction failed", st.message))
                            return@collect
                        }
                    }
                }
                if (!done) return@channelFlow
            }

            send(SetupProgress.Step("Provisioning system files"))
            RootfsProvisioner.provision(rootfs, instance, appSettings.timezone)
            File(rootfs, "tmp").setWritable(true, false)

            send(SetupProgress.Step("Creating user ${req.username}"))
            writeUserScript(rootfs, req, appSettings.timezone)
            val userResult = runner.exec(instance, "bash /tmp/u4a-user.sh ; rm -f /tmp/u4a-user.sh")
            if (!userResult.success) {
                send(SetupProgress.Error("User setup failed", userResult.output.take(400)))
                return@channelFlow
            }
            val passwd = req.password
            runCatching {
                runner.exec(
                    instance,
                    "mkdir -p /home/${req.username}/.vnc && echo '${passwdEscaped(passwd)}' | vncpasswd -f " +
                        "> /home/${req.username}/.vnc/passwd ; chmod 600 /home/${req.username}/.vnc/passwd ; " +
                        "chown -R ${req.username}:${req.username} /home/${req.username}/.vnc",
                )
            }

            send(SetupProgress.Step("apt update"))
            aptStep(instance, "apt-get update", ::send)
            send(SetupProgress.Step("Installing base packages"))
            aptStep(
                instance,
                "DEBIAN_FRONTEND=noninteractive apt-get install -y sudo apt-utils dialog wget nano " +
                    "curl ca-certificates tzdata unzip alsa-utils pulseaudio-utils",
                ::send,
            )

            if (req.desktopEnv != DesktopEnv.NONE) {
                installDesktopSteps(instance, req.desktopEnv, "${1280}x${720}", ::send)
            }

            if (appSettings.audioEnabled) {
                send(SetupProgress.Step("Starting audio bridge"))
                val ok = audio.start(instance)
                send(SetupProgress.Log(if (ok) "pulseaudio ok" else "pulseaudio unavailable — audio disabled"))
            }

            send(SetupProgress.Done)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Last-resort net: any unexpected exception (native proot failure, IO error, …)
            // becomes a visible "Setup failed" card instead of a silent process crash.
            send(SetupProgress.Error("Unexpected error", t.message ?: t::class.simpleName ?: "unknown"))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun installDesktop(instance: DistroInstance, de: DesktopEnv, geometry: String): Flow<SetupProgress> = channelFlow {
        try {
            installDesktopSteps(instance, de, geometry) { send(it) }
            val rootfs = InstancePaths.rootfs(context, instance.id)
            DEScripts.installScripts(rootfs, de, "home/${instance.username}", geometry)
            send(SetupProgress.Done)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            send(SetupProgress.Error("Unexpected error", t.message ?: t::class.simpleName ?: "unknown"))
        }
    }

    private suspend fun installDesktopSteps(
        instance: DistroInstance,
        de: DesktopEnv,
        geometry: String,
        emit: suspend (SetupProgress) -> Unit,
    ) {
        DEScripts.aptSteps(de).forEachIndexed { i, pkgs ->
            emit(SetupProgress.Step("Desktop ${i + 1}/${DEScripts.aptSteps(de).size}", pkgs.joinToString(" ").take(60)))
            aptStep(instance, "DEBIAN_FRONTEND=noninteractive apt-get install -y ${pkgs.joinToString(" ")}", emit)
        }
        if (de != DesktopEnv.NONE) {
            aptStep(instance, "echo '' > /var/lib/dpkg/info/udisks2.postinst ; apt-mark hold udisks2", emit)
            aptStep(instance, "apt-get --fix-broken install ; apt-get clean", emit)
            DEScripts.postCommands(de).forEach { aptStep(instance, it, emit) }
        }
        val rootfs = InstancePaths.rootfs(context, instance.id)
        DEScripts.installScripts(rootfs, de, "home/${instance.username}", geometry)
        DEScripts.installScripts(rootfs, de, "root", geometry)
    }

    private suspend fun aptStep(
        instance: DistroInstance,
        command: String,
        emit: suspend (SetupProgress) -> Unit,
    ) {
        val result = runner.execStreaming(instance, command) { line ->
            if (line.isNotBlank()) emit(SetupProgress.Log(line))
        }
        if (!result.success) emit(SetupProgress.Log("E/ command exited ${result.exitCode}: ${command.take(60)}"))
    }

    private suspend fun importLocal(uri: Uri, dest: File, emit: suspend (SetupProgress) -> Unit) {
        emit(SetupProgress.Step("Reading local archive"))
        val total = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        context.contentResolver.openInputStream(uri).use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = input!!.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) emit(SetupProgress.Percent("Copying archive", done.toFloat() / total, done, total))
                }
            }
        }
    }

    private fun DownloadState.Progress.fraction(): Float =
        if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else 0f

    private fun writeUserScript(rootfs: File, req: InstallRequest, tz: String) {
        val script = buildString {
            appendLine("#!/bin/bash")
            appendLine("set -e")
            appendLine("grep -q '^${req.username}:' /etc/passwd || useradd -m -s /bin/bash ${req.username}")
            appendLine("grep -q '^ubuntu:' /etc/passwd || useradd -m -s /bin/bash ubuntu")
            appendLine("usermod -aG sudo ${req.username} || true")
            appendLine("usermod -aG sudo ubuntu || true")
            appendLine("echo '${req.username}:${singleQuoteEscape(req.password)}' | chpasswd")
            appendLine("echo 'ubuntu:${singleQuoteEscape(req.password)}' | chpasswd")
            appendLine("echo '${req.username}  ALL=(ALL:ALL) ALL' > /etc/sudoers.d/${req.username}")
            appendLine("echo 'ubuntu  ALL=(ALL:ALL) ALL' > /etc/sudoers.d/ubuntu")
            appendLine("chmod 440 /etc/sudoers.d/${req.username} /etc/sudoers.d/ubuntu")
            appendLine("cp -a /etc/skel/. /home/${req.username}/ 2>/dev/null || true")
            appendLine("chown -R ${req.username}:${req.username} /home/${req.username}")
            appendLine("ln -sf /usr/share/zoneinfo/$tz /etc/localtime")
            appendLine("echo $tz > /etc/timezone")
        }
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "tmp/u4a-user.sh").writeText(script)
    }

    private fun singleQuoteEscape(s: String): String = s.replace("'", "'\\''")
    private fun passwdEscaped(s: String): String = s.replace("'", "'\\''")

    fun apt(instance: DistroInstance, args: List<String>): Flow<SetupProgress> = channelFlow {
        try {
            val result = runner.execStreaming(instance, "apt-get ${args.joinToString(" ")}") {
                if (it.isNotBlank()) send(SetupProgress.Log(it))
            }
            send(if (result.success) SetupProgress.Done else SetupProgress.Error("apt failed", "exit ${result.exitCode}"))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            send(SetupProgress.Error("Unexpected error", t.message ?: t::class.simpleName ?: "unknown"))
        }
    }

    companion object {
        fun validateUsername(name: String): String? = when {
            name.isBlank() -> "Username required"
            !Regex("^[a-z_][a-z0-9_-]{0,31}$").matches(name) -> "lowercase letters, digits, _ or -"
            name in RESERVED -> "reserved name"
            else -> null
        }

        private val RESERVED = setOf("root", "daemon", "bin", "sys", "nobody")
    }
}
