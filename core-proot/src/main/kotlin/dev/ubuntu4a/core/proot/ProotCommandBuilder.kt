package dev.ubuntu4a.core.proot

import android.content.Context
import android.os.Build
import dev.ubuntu4a.core.data.InstancePaths
import dev.ubuntu4a.core.data.UbuntuDefaults
import dev.ubuntu4a.core.data.model.BindEntry
import dev.ubuntu4a.core.data.model.DistroInstance
import java.io.File

/**
 * Kotlin port of the generated `.ubuntu` launcher script from `ubuntu.sh`:
 * builds the exact proot argv (binds, env, fake kernel release, workdir).
 */
class ProotCommandBuilder(
    private val context: Context,
    private val binaries: BinaryProvider,
) {

    fun build(
        instance: DistroInstance,
        binds: List<BindEntry>,
        kernelRelease: String,
        asRoot: Boolean,
        command: String?,
        cwd: String = "/root",
    ): List<String> {
        val proot = binaries.requireProot().absolutePath
        binaries.applyProotEnvironment()
        val rootfs = InstancePaths.rootfs(context, instance.id)
        val args = mutableListOf(proot)
        args += listOf(
            "--link2symlink",
            "--kill-on-exit",
            "--kernel-release=$kernelRelease",
            "-0",
            "-r", rootfs.absolutePath,
        )
        for (b in binds) {
            val line = buildString {
                append("-b"); append(b.source); append(':'); append(b.target)
                if (b.readOnly) append(":ro")
            }
            args.add(line)
        }
        args += systemBinds(instance)
        args += "-w"
        args += cwd
        args += "/usr/bin/env"
        args += "-i"
        args += "PATH=${UbuntuDefaults.ENV_PATH}"
        args += "HOME=${if (asRoot) "/root" else "/home/${instance.username}"}"
        args += "LANG=C.UTF-8"
        args += "TMPDIR=/tmp"
        args += "TERM=xterm-256color"
        args += "PULSE_SERVER=127.0.0.1"
        args += "MOZ_FAKE_NO_SANDBOX=1"
        args += "USER=${if (asRoot) "root" else instance.username}"
        args += "LOGNAME=${if (asRoot) "root" else instance.username}"
        val shell = "/bin/bash"
        if (command == null) {
            // -i forces an interactive shell (PS1 prompt) even when stdin/stdout are plain
            // pipes rather than a real PTY (i.e. no NDK pty helper bundled) — without it
            // bash silently runs non-interactively and the terminal looks blank.
            //
            // +m turns *off* bash's job-control ("monitor") mode. proot is ptrace-based and
            // doesn't fully virtualize process groups/sessions, so an interactive bash's
            // normal startup attempt to tcsetpgrp() itself onto the pty is rejected by the
            // kernel with EPERM. bash still starts fine either way, but it prints
            // "cannot set terminal process group ... Permission denied" and "no job control
            // in this shell" every time. Since job control (Ctrl+Z, `fg`/`bg`) never
            // actually works under proot regardless, +m just stops bash from trying and
            // makes those two noisy, harmless-but-alarming lines go away.
            if (asRoot) {
                args += listOf(shell, "+m", "-i", "--login")
            } else {
                // Only the *inner* shell (the one the user actually types into) needs to be
                // interactive. The outer shell here exists purely to hop into `su`; making
                // it non-interactive too means it never attempts tcsetpgrp() at all, instead
                // of failing once here and then failing *again* in the inner shell — which
                // is why the error used to appear twice.
                args += listOf(
                    shell, "+m", "-lc",
                    "exec su - ${instance.username} -c 'exec /bin/bash +m -i'",
                )
            }
        } else {
            args += listOf(shell, "+m", "-lc", command)
        }
        return args
    }

    private fun systemBinds(instance: DistroInstance): List<String> {
        val rootfs = InstancePaths.rootfs(context, instance.id)
        val list = mutableListOf(
            "-b", "/dev",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/dev/null:/proc/sys/kernel/cap_last_cap",
            "-b", "/proc",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "/sys",
        )
        if (File("/sys/fs/selinux").exists()) list += listOf("-b", "/sys/fs/selinux")
        list += listOf("-b", "${rootfs.absolutePath}/root:/dev/shm")
        val sdcard = System.getenv("EXTERNAL_STORAGE") ?: "/storage/emulated/0"
        if (File(sdcard).exists()) list += listOf("-b", "$sdcard:/sdcard")
        list += listOf("-b", context.dataDir.absolutePath + ":/data/data/${context.packageName}")
        return list
    }
}
