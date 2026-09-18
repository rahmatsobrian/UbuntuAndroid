package dev.ubuntu4a.feature.terminal

import dev.ubuntu4a.core.proot.ProotProcess
import dev.ubuntu4a.feature.terminal.vt.VtEmulator
import java.io.ByteArrayOutputStream

class TerminalEngine(initialCols: Int = 80, initialRows: Int = 24) {
    val emulator = VtEmulator(initialCols, initialRows)
    var onInvalidate: (() -> Unit)? = null
    private var proc: ProotProcess? = null

    fun attach(process: ProotProcess) {
        proc = process
        process.resize(emulator.cols, emulator.rows)
        emulator.onResizeRequest = { cols, rows -> process.resize(cols, rows) }
    }

    fun feed(bytes: ByteArray) {
        val filtered = StartupNoiseFilter.strip(bytes)
        if (filtered.isNotEmpty()) {
            emulator.write(filtered)
            onInvalidate?.invoke()
        }
    }

    fun write(bytes: ByteArray) {
        proc?.write(bytes)
    }

    fun detach() {
        proc = null
    }
}

/**
 * Under proot, an interactive bash always tries to tcsetpgrp() itself onto
 * the controlling terminal and the kernel always refuses it (proot doesn't
 * fully virtualize process groups/sessions) — so bash prints:
 *   "bash: cannot set terminal process group (-1): Permission denied"
 *   "bash: no job control in this shell"
 * every single time a shell starts, even with `+m` passed on the command
 * line. Job control (Ctrl+Z, fg/bg) genuinely never works under proot
 * regardless, so these two lines are 100% harmless noise that just scares
 * users into thinking something is broken.
 *
 * IMPORTANT: this only ever inspects a single read() chunk at a time and
 * never holds bytes back across calls. A shell prompt — and every key you
 * type, echoed back by bash — arrives over the PTY with NO trailing '\n'
 * (that only shows up once you press Enter). An earlier version of this
 * filter buffered incomplete lines waiting for a '\n' before releasing
 * anything to the screen, which meant the prompt and your own typed
 * characters could sit invisibly in that buffer forever — a permanently
 * blank terminal that looked exactly like "typed a bunch, nothing shows
 * up". Any trailing partial line (no '\n' yet) is now always passed
 * through immediately, unfiltered, so normal interactive echo can never be
 * delayed. The trade-off is that if this specific short startup warning
 * were ever split exactly across two 8KB reads, one fragment might slip
 * through unfiltered — vastly preferable to freezing real input.
 */
private object StartupNoiseFilter {
    private val noisyPhrases = listOf(
        "cannot set terminal process group",
        "no job control in this shell",
    )

    fun strip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var lineStart = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                val line = bytes.copyOfRange(lineStart, i + 1)
                val text = String(line, Charsets.UTF_8)
                val isNoise = noisyPhrases.any { text.contains(it, ignoreCase = true) }
                if (!isNoise) out.write(line)
                lineStart = i + 1
            }
        }
        // Trailing partial line (prompt / not-yet-Enter-pressed echo) — never held back.
        if (lineStart < bytes.size) out.write(bytes, lineStart, bytes.size - lineStart)
        return out.toByteArray()
    }
}
