package dev.ubuntu4a.core.proot.setup

import dev.ubuntu4a.core.data.model.DistroInstance
import java.io.File

/**
 * Port of the "Writing script to login" + file-fixing section of ubuntu.sh:
 * hostname, hosts, resolv.conf, skeleton dirs, apt deb822 sources.
 */
object RootfsProvisioner {

    fun provision(rootfs: File, instance: DistroInstance, timezone: String) {
        write(rootfs, "etc/hostname", "localhost\n")
        write(rootfs, "etc/hosts", "127.0.0.1 localhost\n")
        write(rootfs, "etc/resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        mkdirs(rootfs, "sdcard", "binds", "data", "tmp", "root", "home/ubuntu", "home/${instance.username}")
        write(
            rootfs,
            "etc/apt/sources.list.d/ubuntu.sources",
            aptSources(instance.codeName, instance.arch),
        )
        write(rootfs, "etc/profile.d/ubuntu4a.sh", envProfile(timezone))
        write(rootfs, "root/.hushlogin", "\n")
        write(rootfs, "home/ubuntu/.hushlogin", "\n")
        write(rootfs, "home/${instance.username}/.hushlogin", "\n")
        File(rootfs, "etc/localtime").delete()
    }

    private fun envProfile(timezone: String) = """
       |# >>> Ubuntu for Android >>>
        |export PULSE_SERVER=127.0.0.1
        |export DISPLAY=:1
        |export MOZ_FAKE_NO_SANDBOX=1
        |export TZ=$timezone
        |cd
       |# <<< Ubuntu for Android <<<
        |""".trimMargin()

    fun aptSources(codeName: String, arch: String): String {
        val uri = if (arch == "amd64" || arch == "i386") "http://archive.ubuntu.com/ubuntu/"
        else "http://ports.ubuntu.com/ubuntu-ports/"
        return buildString {
            appendLine("## Managed by Ubuntu for Android")
            appendLine("Types: deb")
            appendLine("URIs: $uri")
            appendLine(
                "Suites: $codeName $codeName-updates $codeName-security $codeName-backports",
            )
            appendLine("Components: main restricted universe multiverse")
            append("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg")
        }
    }

    fun write(rootfs: File, relPath: String, content: String) {
        val f = File(rootfs, relPath)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun mkdirs(rootfs: File, vararg paths: String) {
        paths.forEach { File(rootfs, it).mkdirs() }
    }
}
