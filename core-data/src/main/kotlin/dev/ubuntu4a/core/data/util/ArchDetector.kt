package dev.ubuntu4a.core.data.util

import android.os.Build

object ArchDetector {

    data class DeviceArch(val androidAbi: String, val rootfsArch: String, val is64Bit: Boolean)

    val current: DeviceArch by lazy {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        when {
            abi.contains("arm64") || abi == "aarch64" -> DeviceArch(abi, "arm64", true)
            abi.contains("armeabi") || abi.startsWith("arm") -> DeviceArch(abi, "armhf", false)
            abi.contains("x86_64") -> DeviceArch(abi, "amd64", true)
            abi.contains("x86") -> DeviceArch(abi, "i386", false)
            else -> DeviceArch(abi, "arm64", true)
        }
    }

    fun supportsAbi(abi: String): Boolean = Build.SUPPORTED_ABIS.any { it.contains(abi) }

    fun isWx64Compatible(): Boolean {
        val a = current.androidAbi
        return a.contains("arm64") || a.contains("x86_64")
    }
}
