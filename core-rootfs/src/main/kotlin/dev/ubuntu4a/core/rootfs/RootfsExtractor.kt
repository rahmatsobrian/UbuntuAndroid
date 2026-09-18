package dev.ubuntu4a.core.rootfs

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

sealed interface ExtractState {
    data class Progress(val entries: Int, val bytes: Long, val current: String) : ExtractState
    data class Done(val entries: Int, val bytes: Long) : ExtractState
    data class Failed(val message: String) : ExtractState
}

/**
 * Streaming tar.{gz,xz,bz2} extractor with `proot --link2symlink` semantics:
 * hard links are recreated as symbolic links pointing at the already-extracted
 * member, which is exactly how the reference `ubuntu.sh` unpacks rootfs without
 * root. Ownership/permissions from the archive are best-effort (no chown as
 * untrusted app); modes are applied where possible.
 */
class RootfsExtractor {

    fun extract(archive: File, rootfs: File): Flow<ExtractState> = flow {
        var entries = 0
        var bytes = 0L
        rootfs.mkdirs()
        val linkTargets = HashMap<String, String>()
        try {
            val raw = BufferedInputStream(FileInputStream(archive), 256 * 1024)
            val decompressed = wrapKnownCompression(raw)
            TarArchiveInputStream(decompressed).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.removePrefix("./").removePrefix("/")
                    if (name.isBlank()) continue
                    val out = File(rootfs, name)
                    if (!out.canonicalPath.startsWith(rootfs.canonicalPath + File.separator) &&
                        out.canonicalPath != rootfs.canonicalPath
                    ) continue
                    when {
                        entry.isDirectory -> {
                            out.mkdirs()
                        }
                        entry.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            runCatching { Os.symlink(entry.linkName, out.absolutePath) }
                                .onFailure { writeFallbackSymlink(out, entry.linkName) }
                        }
                        entry.isLink -> {
                            val resolved = linkTargets[entry.linkName] ?: entry.linkName
                            out.parentFile?.mkdirs()
                            out.delete()
                            runCatching { Os.symlink(resolved, out.absolutePath) }
                                .onFailure { writeFallbackSymlink(out, resolved) }
                        }
                        entry.isFile -> {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { copy ->
                                val buf = ByteArray(128 * 1024)
                                while (true) {
                                    val n = tar.read(buf)
                                    if (n < 0) break
                                    copy.write(buf, 0, n)
                                    bytes += n
                                }
                            }
                            linkTargets[name] = out.absolutePath
                            applyMode(out, entry.mode)
                        }
                    }
                    entries++
                    if (entries % 400 == 0) emit(ExtractState.Progress(entries, bytes, name))
                }
            }
            emit(ExtractState.Done(entries, bytes))
        } catch (t: Throwable) {
            emit(ExtractState.Failed(t.message ?: t.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    private fun wrapKnownCompression(input: InputStream): InputStream {
        input.mark(8)
        val magic = ByteArray(6).also { input.read(it) }
        input.reset()
        return when {
            magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> GzipCompressorInputStream(input, true)
            magic[0] == 0xfd.toByte() && magic[1] == '7'.code.toByte() && magic[2] == 'z'.code.toByte() &&
                magic[3] == 'X'.code.toByte() -> XZCompressorInputStream(input)
            magic[0] == 'B'.code.toByte() && magic[1] == 'Z'.code.toByte() -> BZip2CompressorInputStream(input)
            else -> input
        }
    }

    private fun applyMode(file: File, mode: Int) {
        val exec = mode and 0x40 != 0 || mode and 0x01 != 0
        if (exec) file.setExecutable(true, false)
        file.setWritable(true, false)
    }

    private fun writeFallbackSymlink(link: File, target: String) {
        link.parentFile?.mkdirs()
        FileOutputStream(link).use { it.write("symlink:$target".toByteArray()) }
    }
}
