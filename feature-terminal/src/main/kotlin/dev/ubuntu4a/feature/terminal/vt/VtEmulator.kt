package dev.ubuntu4a.feature.terminal.vt

import java.nio.charset.StandardCharsets

class TerminalCell(var char: Char = ' ', var fg: Long = NO_COLOR, var bg: Long = NO_COLOR, var style: Int = STYLE_NORMAL) {
    companion object {
        const val NO_COLOR = -1L
        const val STYLE_NORMAL = 0
        const val STYLE_BOLD = 1
        const val STYLE_UNDERLINE = 2
        const val STYLE_INVERSE = 4
        const val STYLE_DIM = 8
        const val STYLE_ITALIC = 16
    }
    fun copyFrom(o: TerminalCell) { char = o.char; fg = o.fg; bg = o.bg; style = o.style }
}

/**
 * Self-contained ANSI/VT100(xterm) screen emulator: SGR(16/256/RGB),
 * cursor addressing, scrolling regions, alternate screen, wrap-around.
 * Enough for bash, apt output, vim/nano, htop, less, neofetch.
 */
class VtEmulator(cols: Int = 80, rows: Int = 24) {

    var cols = cols
        private set
    var rows = rows
        private set

    private var main = Array(rows) { Array(cols) { TerminalCell() } }
    private var alt = Array(rows) { Array(cols) { TerminalCell() } }
    private var buffer = main
    private var cursorX = 0
    private var cursorY = 0
    private var savedX = 0
    private var savedY = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var autoWrap = true
    private var wrapPending = false
    private var applicationCursorKeys = false
    private var cursorVisible = true
    var bell = false
        private set

    private var fgCur = TerminalCell.NO_COLOR
    private var bgCur = TerminalCell.NO_COLOR
    private var styleCur = TerminalCell.STYLE_NORMAL

    private val decoder = CharsetDecoderBridge()

    var onResizeRequest: ((Int, Int) -> Unit)? = null
    var onTitle: ((String) -> Unit)? = null

    val screenVersion = IntArray(1)
    fun bump(): Int { screenVersion[0]++; return screenVersion[0] }

    data class Snapshot(val lines: List<Array<TerminalCell>>, val cursorX: Int, val cursorY: Int, val visible: Boolean)

    fun snapshot(): Snapshot {
        val copy = Array(buffer.size) { y ->
            Array(buffer[y].size) { x -> TerminalCell().also { it.copyFrom(buffer[y][x]) } }
        }
        return Snapshot(copy.toList(), cursorX, cursorY, cursorVisible)
    }

    fun write(bytes: ByteArray) {
        decoder.feed(bytes) { code -> process(code) }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols == this.cols && rows == this.rows) return
        val newMain = Array(rows) { y -> Array(cols) { x -> if (y < main.size && x < main[y].size) main[y][x] else TerminalCell() } }
        val newAlt = Array(rows) { y -> Array(cols) { x -> if (y < alt.size && x < alt[y].size) alt[y][x] else TerminalCell() } }
        main = newMain
        alt = newAlt
        buffer = if (buffer === main) main else alt
        this.cols = cols
        this.rows = rows
        scrollTop = 0
        scrollBottom = rows - 1
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        onResizeRequest?.invoke(cols, rows)
        bump()
    }

    // ---------------- parser ----------------
    private enum class State { GROUND, ESC, CSI, OSC, CHARSET_SELECT }
    private var state = State.GROUND
    private val params = mutableListOf<Int>()
    private val dcsBuf = StringBuilder()
    private var privateMarker: Char? = null
    private var oscBuf = StringBuilder()

    private fun process(cp: Int) {
        when (state) {
            State.GROUND -> when (cp) {
                0x1b -> { state = State.ESC; params.clear(); privateMarker = null }
                '\n'.code -> { lineFeed() }
                '\r'.code -> { cursorX = 0; wrapPending = false }
                '\b'.code -> { if (cursorX > 0) cursorX--; wrapPending = false }
                '\t'.code -> { cursorX = ((cursorX / 8) + 1) * 8 - 1; if (cursorX >= cols) cursorX = cols - 1 }
                0x07 -> { bell = true }
                0x0e, 0x0f -> { /* GL/GR charset switch: ignored */ }
                else -> { if (cp >= 0x20) printChar(cp) }
            }
            State.ESC -> when (cp) {
                '['.code -> { state = State.CSI; params.clear(); privateMarker = null; dcsBuf.clear() }
                ']'.code -> { state = State.OSC; oscBuf.clear() }
                '('.code, ')'.code, '*'.code, '+'.code -> { state = State.CHARSET_SELECT }
                'M'.code -> reverseIndex()
                'D'.code -> index()
                'E'.code -> { lineFeed(); cursorX = 0 }
                'c'.code -> hardReset()
                '7'.code -> { savedX = cursorX; savedY = cursorY }
                '8'.code -> { cursorX = savedX; cursorY = savedY }
                'H'.code -> { /* tab set: ignored */ }
                '='.code -> applicationCursorKeys = true
                '>'.code -> applicationCursorKeys = false
                else -> { state = State.GROUND; process(cp) }
            }
            State.CHARSET_SELECT -> { state = State.GROUND }
            State.CSI -> parseCsi(cp)
            State.OSC -> parseOsc(cp)
        }
    }

    private fun parseCsi(cp: Int) {
        when {
            cp in '0'.code..'9'.code -> {
                val d = cp - '0'.code
                if (params.isEmpty()) params.add(d) else params[params.size - 1] = params.last() * 10 + d
            }
            cp == ';'.code || cp == ':'.code -> params.add(0)
            cp in 0x40..0x7e -> {
                handleCsi(privateMarker, cp)
                state = State.GROUND
            }
            cp == '?'.code || cp == '>'.code || cp == '!'.code -> {
                if (privateMarker == null) privateMarker = cp.toChar()
            }
            else -> { state = State.GROUND }
        }
    }

    private fun p(index: Int, default: Int) = params.getOrNull(index)?.takeIf { it != 0 } ?: default
    private fun pRaw(index: Int, default: Int) = params.getOrNull(index) ?: default

    private fun handleCsi(private: Char?, final: Int) {
        when (final.toChar()) {
            'A' -> moveCursor(0, -p(0, 1))
            'B' -> moveCursor(0, p(0, 1))
            'C' -> moveCursor(p(0, 1), 0)
            'D' -> moveCursor(-p(0, 1), 0)
            'E' -> { cursorY = (cursorY + p(0, 1)).coerceAtMost(rows - 1); cursorX = 0 }
            'F' -> { cursorY = (cursorY - p(0, 1)).coerceAtLeast(0); cursorX = 0 }
            'G', '`' -> setCursor(p(0, 1) - 1, cursorY)
            'd' -> setCursor(cursorX, p(0, 1) - 1)
            'H', 'f' -> setCursor(p(0, 1) - 1, p(1, 1) - 1)
            'J' -> eraseDisplay(pRaw(0, 0))
            'K' -> eraseLine(pRaw(0, 0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'r' -> {
                scrollTop = (pRaw(0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (p(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                setCursor(0, 0)
            }
            'h' -> setMode(private, pRaw(0, 0), true)
            'l' -> setMode(private, pRaw(0, 0), false)
            's' -> { savedX = cursorX; savedY = cursorY }
            'u' -> setCursor(savedX, savedY)
            'm' -> applySgr()
            'S' -> repeat(p(0, 1)) { scrollUpOne() }
            'T' -> repeat(p(0, 1)) { scrollDownOne() }
            'c' -> { /* device attributes: swallow */ }
            'n' -> { /* device status */ }
        }
        bump()
    }

    private fun setMode(private: Char?, mode: Int, enabled: Boolean) {
        when {
            private == '?' && mode == 1049 || private == '?' && mode == 47 || private == '?' && mode == 1047 -> {
                if (enabled) {
                    if (buffer === main) {
                        if (mode == 1049) alt = Array(rows) { Array(cols) { TerminalCell() } }
                        buffer = alt
                    }
                } else {
                    buffer = main
                }
                cursorVisible = true
            }
            private == '?' && mode == 25 -> cursorVisible = enabled
            private == '?' && mode == 1 -> applicationCursorKeys = enabled
            private == '?' && mode == 7 -> autoWrap = enabled
            private == '?' && mode == 2004 -> { /* bracketed paste — accepted, ignored */ }
            private == null && mode == 4 -> insertModeHack(enabled)
            else -> { /* unknown: ignore */ }
        }
    }

    private fun insertModeHack(enabled: Boolean) { /* rare; no-op */ }

    private fun parseOsc(cp: Int) {
        when (cp) {
            '\u0007'.code -> { commitOsc(); state = State.GROUND }
            0x1b -> { /* eat ESC \ */ }
            '\\'.code -> { if (oscBuf.endsWith("\u001b")) oscBuf.deleteAt(oscBuf.length - 1); commitOsc(); state = State.GROUND }
            else -> oscBuf.append(cp.toChar())
        }
    }

    private fun commitOsc() {
        val text = oscBuf.toString()
        val title = when {
            text.startsWith("0;") || text.startsWith("2;") -> text.substring(2)
            text.startsWith("1;") -> text.substring(2)
            else -> null
        }
        if (title != null) onTitle?.invoke(title)
    }

    // ---------------- output ----------------
    private fun printChar(cp: Int) {
        val ch = String(Character.toChars(cp)).first()
        if (autoWrap && wrapPending) {
            cursorX = 0
            lineFeed()
            wrapPending = false
        }
        val x = cursorX.coerceAtMost(cols - 1)
        val cell = buffer[cursorY][x]
        cell.char = ch
        cell.fg = fgCur
        cell.bg = bgCur
        cell.style = styleCur
        if (autoWrap && cursorX >= cols - 1) wrapPending = true else if (cursorX < cols) cursorX++
    }

    private fun moveCursor(dx: Int, dy: Int) = setCursor(cursorX + dx, cursorY + dy)

    private fun setCursor(x: Int, y: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(0, rows - 1)
        wrapPending = false
    }

    private fun index() {
        if (cursorY == scrollBottom) scrollUpOne() else if (cursorY < rows - 1) cursorY++
    }

    private fun lineFeed() = index()

    private fun reverseIndex() {
        if (cursorY == scrollTop) scrollDownOne() else if (cursorY > 0) cursorY--
    }

    private fun scrollUpOne() {
        for (y in scrollTop until scrollBottom) buffer[y] = buffer[y + 1]
        buffer[scrollBottom] = Array(cols) { TerminalCell().also { it.bg = bgBlank() } }
    }

    private fun scrollDownOne() {
        for (y in scrollBottom downTo scrollTop + 1) buffer[y] = buffer[y - 1]
        buffer[scrollTop] = Array(cols) { TerminalCell().also { it.bg = bgBlank() } }
    }

    private fun bgBlank(): Long = if (styleCur and TerminalCell.STYLE_INVERSE != 0) fgCur else bgCur

    private fun insertLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        repeat(n) {
            for (y in scrollBottom downTo cursorY + 1) buffer[y] = buffer[y - 1]
            buffer[cursorY] = blankRow()
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        repeat(n) {
            for (y in cursorY until scrollBottom) buffer[y] = buffer[y + 1]
            buffer[scrollBottom] = blankRow()
        }
    }

    private fun insertChars(n: Int) {
        val row = buffer[cursorY]
        repeat(n) {
            for (x in cols - 1 downTo cursorX + 1) row[x].copyFrom(row[x - 1])
            row[cursorX] = TerminalCell(bg = bgCur)
        }
    }

    private fun deleteChars(n: Int) {
        val row = buffer[cursorY]
        repeat(n) {
            for (x in cursorX until cols - 1) row[x].copyFrom(row[x + 1])
            row[cols - 1] = TerminalCell(bg = bgCur)
        }
    }

    private fun eraseChars(n: Int) {
        repeat(n.coerceAtMost(cols - cursorX)) { buffer[cursorY][cursorX + it] = TerminalCell(bg = bgCur) }
    }

    private fun blankRow(): Array<TerminalCell> = Array(cols) { TerminalCell(bg = bgBlank()) }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (y in cursorY + 1 until rows) buffer[y] = blankRow() }
            1 -> { eraseLine(1); for (y in 0 until cursorY) buffer[y] = blankRow() }
            2, 3 -> { for (y in 0 until rows) buffer[y] = blankRow() }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = buffer[cursorY]
        when (mode) {
            0 -> for (x in cursorX until cols) row[x] = TerminalCell(bg = bgCur)
            1 -> for (x in 0..cursorX.coerceAtMost(cols - 1)) row[x] = TerminalCell(bg = bgCur)
            2 -> for (x in 0 until cols) row[x] = TerminalCell(bg = bgCur)
        }
    }

    private fun applySgr() {
        if (params.isEmpty()) params.add(0)
        var i = 0
        while (i < params.size) {
            when (val v = params[i]) {
                0 -> { fgCur = TerminalCell.NO_COLOR; bgCur = TerminalCell.NO_COLOR; styleCur = TerminalCell.STYLE_NORMAL }
                1 -> styleCur = styleCur or TerminalCell.STYLE_BOLD
                2 -> styleCur = styleCur or TerminalCell.STYLE_DIM
                3 -> styleCur = styleCur or TerminalCell.STYLE_ITALIC
                4 -> styleCur = styleCur or TerminalCell.STYLE_UNDERLINE
                7 -> styleCur = styleCur or TerminalCell.STYLE_INVERSE
                22 -> styleCur = styleCur and TerminalCell.STYLE_BOLD.inv() and TerminalCell.STYLE_DIM.inv() and TerminalCell.STYLE_ITALIC.inv()
                24 -> styleCur = styleCur and TerminalCell.STYLE_UNDERLINE.inv()
                27 -> styleCur = styleCur and TerminalCell.STYLE_INVERSE.inv()
                in 30..37 -> fgCur = ansiPalette(v - 30)
                38 -> i = extendedColor(i) { fgCur = it }
                39 -> fgCur = TerminalCell.NO_COLOR
                in 40..47 -> bgCur = ansiPalette(v - 40)
                48 -> i = extendedColor(i) { bgCur = it }
                49 -> bgCur = TerminalCell.NO_COLOR
                in 90..97 -> fgCur = ansiPalette(v - 90 + 8)
                in 100..107 -> bgCur = ansiPalette(v - 100 + 8)
            }
            i++
        }
    }

    private fun extendedColor(i: Int, set: (Long) -> Unit): Int {
        val kind = pRaw(i + 1, 0)
        return when (kind) {
            5 -> { set(ansi256(pRaw(i + 2, 0))); i + 2 }
            2 -> {
                val r = pRaw(i + 2, 0); val g = pRaw(i + 3, 0); val b = pRaw(i + 4, 0)
                set((0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())); i + 4
            }
            else -> i + 1
        }
    }

    private fun ansiPalette(index: Int): Long = ansi16[index.coerceIn(0, 15)]

    companion object {
        val ansi16 = longArrayOf(
            0xFF000000L, 0xFFCD3131L, 0xFF0DBC79L, 0xFFE3E18DL, 0xFF2472C8L, 0xFFBC3FBCL, 0xFF11A8CDL, 0xFFE5E5E5L,
            0xFF666666L, 0xFFF14C4CL, 0xFF23D18BL, 0xFFF5F543L, 0xFF3B8EEAL, 0xFFD670D6L, 0xFF29B8DBL, 0xFFFFFFFFL,
        )

        fun ansi256(index: Int): Long {
            if (index < 16) return ansi16[index]
            if (index in 16..231) {
                val i = index - 16
                val r = i / 36
                val g = (i % 36) / 6
                val b = i % 6
                fun tone(v: Int) = if (v == 0) 0 else 55 + v * 40
                return 0xFF000000L or (tone(r).toLong() shl 16) or (tone(g).toLong() shl 8) or tone(b).toLong()
            }
            val gray = 8 + (index - 232) * 10
            return 0xFF000000L or (gray.toLong() shl 16) or (gray.toLong() shl 8) or gray.toLong()
        }
    }

    fun hardReset() {
        main = Array(rows) { Array(cols) { TerminalCell() } }
        alt = Array(rows) { Array(cols) { TerminalCell() } }
        buffer = main
        cursorX = 0; cursorY = 0; scrollTop = 0; scrollBottom = rows - 1
        fgCur = TerminalCell.NO_COLOR; bgCur = TerminalCell.NO_COLOR; styleCur = TerminalCell.STYLE_NORMAL
        autoWrap = true; cursorVisible = true; wrapPending = false
        bump()
    }

    // Key encoding for the input side
    fun keyBytes(key: String, ctrl: Boolean, alt: Boolean): ByteArray {
        val special = when (key) {
            "UP" -> if (applicationCursorKeys) "\u001bOA" else "\u001b[A"
            "DOWN" -> if (applicationCursorKeys) "\u001bOB" else "\u001b[B"
            "RIGHT" -> if (applicationCursorKeys) "\u001bOC" else "\u001b[C"
            "LEFT" -> if (applicationCursorKeys) "\u001bOD" else "\u001b[D"
            "HOME" -> "\u001b[H"
            "END" -> "\u001b[F"
            "PGUP" -> "\u001b[5~"
            "PGDN" -> "\u001b[6~"
            "INS" -> "\u001b[2~"
            "DEL" -> "\u001b[3~"
            "ENTER" -> "\n"
            "SPACE" -> " "
            "TAB" -> "\t"
            "ESC" -> "\u001b"
            "BACKSPACE" -> "\u007f"
            else -> null
        }
        var text = special ?: key
        if (ctrl && text.length == 1) {
            val c = text.first().lowercaseChar()
            when {
                c in 'a'..'z' -> text = String(byteArrayOf((c.code - 96).toByte()))
                c == ' ' -> text = "\u0000"
            }
        }
        if (alt) text = "\u001b$text"
        return text.toByteArray(StandardCharsets.UTF_8)
    }
}
