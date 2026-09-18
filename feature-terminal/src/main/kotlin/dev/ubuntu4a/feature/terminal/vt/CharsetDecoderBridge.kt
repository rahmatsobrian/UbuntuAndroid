package dev.ubuntu4a.feature.terminal.vt

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Incremental UTF-8 → code point decoder used by [VtEmulator]. */
class CharsetDecoderBridge {
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .replaceWith("?")
    private val cb: CharBuffer = CharBuffer.allocate(4096)
    private var pendingHigh: Char? = null

    fun feed(bytes: ByteArray, emit: (Int) -> Unit) {
        var bb = ByteBuffer.wrap(bytes)
        while (bb.hasRemaining()) {
            cb.clear()
            val res = decoder.decode(bb, cb, false)
            cb.flip()
            while (cb.hasRemaining()) {
                val c = cb.get()
                val high = pendingHigh
                if (high != null && Character.isLowSurrogate(c)) {
                    pendingHigh = null
                    emit(Character.toCodePoint(high, c))
                } else if (Character.isHighSurrogate(c)) {
                    pendingHigh = c
                } else {
                    pendingHigh = null
                    emit(c.code)
                }
            }
            cb.clear()
            if (res.isUnderflow) break
        }
    }

    fun flushLine() {
        pendingHigh = null
    }
}
