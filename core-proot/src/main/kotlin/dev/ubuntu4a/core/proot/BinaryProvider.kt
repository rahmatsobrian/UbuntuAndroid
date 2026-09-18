package dev.ubuntu4a.core.proot

import android.content.Context
import dev.ubuntu4a.core.data.model.DistroInstance
import java.io.File

/**
 * Statically linked binaries are shipped renamed to `lib*.so` inside
 * `jniLibs/<abi>/` so the packaging keeps them and they end up (executable,
 * read-only, whitelisted for exec) in [Context.applicationInfo]'s
 * `nativeLibraryDir`. See README for build/alignment instructions.
 */
class BinaryProvider(private val context: Context) {

    private val nativeDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    fun prootBinary(): File? = candidates("proot").firstOrNull { it.isFile }

    fun pulseAudioBinary(): File? = candidates("pulseaudio").firstOrNull { it.isFile }

    fun tarBinary(): File? = candidates("tar").firstOrNull { it.isFile }

    /** Optional accelerated ptrace/seccomp loader that some proot builds (e.g. Termux's)
     *  expect to find via PROOT_LOADER / PROOT_LOADER_32 rather than baked-in paths. */
    fun prootLoaderBinary(): File? = candidates("proot-loader").firstOrNull { it.isFile }
    fun prootLoader32Binary(): File? = candidates("proot-loader32").firstOrNull { it.isFile }

    private fun candidates(base: String): List<File> = listOf(
        File(nativeDir, "libubuntu-$base.so"),
        File(nativeDir, "lib$base.so"),
        File(context.filesDir, "bin/$base"),
    )

    val isProotAvailable: Boolean get() = prootBinary() != null

    /** Writable scratch dir proot needs for its own bookkeeping — default `/tmp` only
     *  exists inside Termux, not for a general app on Android. */
    fun prootTmpDir(): File = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    @Volatile private var envApplied = false

    /** Sets PROOT_TMP_DIR / PROOT_LOADER / PROOT_LOADER_32 on this process's environment
     *  so they're inherited by every proot child, whether launched via ProcessBuilder or
     *  the native forkpty() path in PtyNative — both inherit the current process environ.
     *  Safe to call repeatedly; only takes effect once. */
    fun applyProotEnvironment() {
        if (envApplied) return
        envApplied = true
        runCatching { android.system.Os.setenv("PROOT_TMP_DIR", prootTmpDir().absolutePath, true) }
        prootLoaderBinary()?.let { loader ->
            runCatching { android.system.Os.setenv("PROOT_LOADER", loader.absolutePath, true) }
        }
        prootLoader32Binary()?.let { loader32 ->
            runCatching { android.system.Os.setenv("PROOT_LOADER_32", loader32.absolutePath, true) }
        }
    }

    fun requireProot(): File = prootBinary()
        ?: throw ProotBinaryMissingException()
}

class ProotBinaryMissingException : Exception(
    "proot binary not found. Bundle a 16KB-page-aligned static proot as " +
        "app/src/main/jniLibs/<abi>/libubuntu-proot.so (see README).",
)
