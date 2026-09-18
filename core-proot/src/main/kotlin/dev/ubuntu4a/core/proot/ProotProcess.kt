package dev.ubuntu4a.core.proot

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * A running Linux process (usually `proot … bash`) either on a real PTY
 * (interactive) or on pipes (headless commands).
 */
class ProotProcess internal constructor(
    private val pid: Int?,
    private val masterFd: Int?,
    private val process: Process?,
) {
    private val _output = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val output: Flow<ByteArray> = _output.asSharedFlow()

    private val stdin: OutputStream?
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var destroyed = false

    @Volatile
    var exitCode: Int? = null
        private set

    val isAlive: Boolean
        get() = !destroyed && (process?.isAlive ?: runCatching {
            android.system.Os.kill(pid ?: 0, 0)
            true
        }.getOrDefault(false))

    init {
        val input = when {
            masterFd != null -> {
                val pfd = ParcelFileDescriptor.adoptFd(masterFd)
                stdin = FileOutputStream(pfd.fileDescriptor)
                FileInputStream(pfd.fileDescriptor)
            }
            process != null -> {
                stdin = process.outputStream
                process.inputStream
            }
            else -> {
                stdin = null
                null
            }
        }
        if (input != null) startReader(input)
        if (masterFd != null && pid != null) {
            scope.launch {
                runCatching { exitCode = PtyNative.reap(pid) }
                destroyed = true
            }
        }
        if (process != null) {
            scope.launch {
                // Was unguarded: destroy() below calls process.destroy(), which closes this
                // exact stream out from under a possibly-still-reading useLines{} on another
                // thread. The resulting IOException had nowhere to go but this coroutine's
                // (SupervisorJob-less-for-its-own-failure) uncaught path, which crashes the
                // whole app a moment later — exactly the "back to menu, then force close"
                // symptom, on devices where the native pty helper isn't available and this
                // ProcessBuilder fallback is the one actually in use.
                runCatching {
                    process.errorStream.reader().useLines { lines ->
                        for (line in lines) _output.emit(line.toByteArray())
                    }
                }
            }
            scope.launch {
                runCatching { exitCode = process.waitFor() }
                destroyed = true
            }
        }
    }

    private fun startReader(input: java.io.InputStream) {
        readerJob = scope.launch {
            val buf = ByteArray(8192)
            runCatching {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    _output.emit(buf.copyOf(n))
                }
            }
            destroyed = true
        }
    }

    fun write(bytes: ByteArray) {
        runCatching {
            stdin?.write(bytes)
            stdin?.flush()
        }
    }

    fun write(s: String) = write(s.toByteArray())

    fun resize(cols: Int, rows: Int) {
        masterFd?.let { if (PtyNative.available) runCatching { PtyNative.resize(it, cols, rows) } }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        readerJob?.cancel()
        runCatching { stdin?.close() }
        pid?.let { p ->
            runCatching { android.system.Os.kill(p, 15) }
            Thread.sleep(200)
            runCatching { android.system.Os.kill(p, 9) }
        }
        process?.destroy()
    }
}

object ProotLauncher {

    fun start(argv: List<String>, cols: Int = 80, rows: Int = 24): ProotProcess {
        if (PtyNative.available) {
            val res = PtyNative.fork(cols, rows, argv.toTypedArray())
            if (res[0] > 0) {
                return ProotProcess(pid = res[0], masterFd = res[1], process = null)
            }
        }
        val pb = ProcessBuilder(argv)
            .redirectErrorStream(false)
        val proc = pb.start()
        return ProotProcess(pid = null, masterFd = null, process = proc)
    }
}
