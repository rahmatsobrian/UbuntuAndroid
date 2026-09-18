package dev.ubuntu4a.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ReleaseStatus { STABLE, EXPERIMENTAL, DEVEL }

enum class RootfsSource { RELEASE, OCI, LOCAL }

@Serializable
data class UbuntuRelease(
    val codeName: String,
    val version: String,
    val label: String,
    val status: ReleaseStatus = ReleaseStatus.STABLE,
    val lts: Boolean = false,
)

enum class DesktopEnv(val displayName: String, val startCmd: String) {
    NONE("CLI Only", ""),
    XFCE("XFCE4", "startxfce4"),
    LXQT("LXQt", "startlxqt"),
    LXDE("LXDE", "startlxde"),
    KDE("KDE Plasma", "startplasma-x11"),
}

@Serializable
enum class InstanceStatus { NOT_INSTALLED, INSTALLING, INSTALLED, RUNNING }

@Serializable
data class DistroInstance(
    @SerialName("id") val id: String,
    val codeName: String,
    val version: String,
    val arch: String,
    val username: String,
    val desktopEnv: DesktopEnv = DesktopEnv.NONE,
    val status: InstanceStatus = InstanceStatus.INSTALLED,
    val createdAt: Long = 0L,
    val sizeBytes: Long = 0L,
)

data class SetupStep(val name: String, val detail: String = "")

sealed interface SetupProgress {
    data class Step(val label: String, val detail: String? = null, val done: Boolean = false, val failed: Boolean = false) : SetupProgress
    data class Percent(val label: String, val fraction: Float, val transferred: Long = 0, val total: Long = -1) : SetupProgress
    data class Log(val line: String) : SetupProgress
    data object Done : SetupProgress
    data class Error(val message: String, val cause: String? = null) : SetupProgress
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class BindEntry(val source: String, val target: String = source, val readOnly: Boolean = false) {
    fun toLine() = "$source:$target${if (readOnly) ":ro" else ""}"
    companion object {
        fun parse(line: String): BindEntry? {
            val parts = line.trim().split(":")
            if (parts.size < 2 || parts[0].isBlank()) return null
            return BindEntry(parts[0], parts[1], parts.getOrNull(2) == "ro")
        }
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val terminalFontSizeSp: Float = 12f,
    val desktopWidth: Int = 1280,
    val desktopHeight: Int = 720,
    val audioEnabled: Boolean = true,
    val kernelReleaseSpoof: String = "6.18.3-fake",
    val rootfsMirror: String = "https://partner-images.canonical.com/oci",
    val rootfsSource: RootfsSource = RootfsSource.RELEASE,
    val timezone: String = "Asia/Jakarta",
)
