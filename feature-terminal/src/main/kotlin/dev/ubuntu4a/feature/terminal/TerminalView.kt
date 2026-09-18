package dev.ubuntu4a.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import dev.ubuntu4a.feature.terminal.vt.TerminalCell
import dev.ubuntu4a.feature.terminal.vt.VtEmulator
import kotlin.math.max
import kotlin.math.min

/**
 * Grid-rendered terminal surface (xterm-256color). Pinch-zoom changes the
 * font size, IME text and hardware keys are forwarded through a
 * [BaseInputConnection] just like Termux's TerminalView does.
 */
class TerminalView(context: Context) : View(context) {

    var emulator: VtEmulator? = null
    var onBytes: ((ByteArray) -> Unit)? = null
    var ctrlHeld = false
    var altHeld = false

    /** When true, touches drag out a text selection instead of typing/focusing the IME. */
    var selectionMode = false
        set(value) {
            field = value
            if (!value) clearSelection() else postInvalidate()
        }

    /** Called once a drag produces a non-empty selection, so the UI can show a Copy action. */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    private var selStartRow = -1
    private var selStartCol = -1
    private var selEndRow = -1
    private var selEndCol = -1
    private val hasSelection: Boolean get() = selStartRow >= 0 && selEndRow >= 0
    private val selectionPaint = Paint().apply { color = 0x668AB4F8.toInt() }

    var fontSizePx: Float = resources.displayMetrics.scaledDensity * 13f
        set(value) {
            val v = value.coerceIn(8f * resources.displayMetrics.scaledDensity, 30f * resources.displayMetrics.scaledDensity)
            if (field != v) {
                field = v
                requestGrid()
                postInvalidate()
            }
        }

    var defaultFg: Int = 0xFFE5E5E5.toInt()
    var defaultBg: Int = 0xFF0C0C0C.toInt()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val cursorPaint = Paint()
    private var charWidth = 0f
    private var lineHeight = 0f
    private var baseline = 0f
    private var cols = 80
    private var rows = 24
    private var cursorOn = true
    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            postInvalidateOnAnimation()
            postDelayed(this, 530L)
        }
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fontSizePx *= detector.scaleFactor
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        textPaint.typeface = android.graphics.Typeface.MONOSPACE
        textPaint.isSubpixelText = false
    }

    val terminalCols: Int get() = cols
    val terminalRows: Int get() = rows

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(blink)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    private fun updatePaintMetrics() {
        textPaint.textSize = fontSizePx
        val metrics = textPaint.fontMetrics
        charWidth = textPaint.measureText("M")
        lineHeight = metrics.descent - metrics.ascent + metrics.leading
        baseline = -metrics.ascent
    }

    private fun requestGrid() {
        updatePaintMetrics()
        val c = (width / charWidth).toInt().coerceAtLeast(20)
        val r = (height / lineHeight).toInt().coerceAtLeast(6)
        if (c != cols || r != rows) {
            cols = c
            rows = r
            emulator?.resize(cols, rows)
        }
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0 && h > 0) requestGrid()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (selectionMode) {
            handleSelectionTouch(event)
            return true
        }
        scale.onTouchEvent(event)
        if (!hasFocus()) requestFocus()
        performClick()
        // Focus alone doesn't reliably pop the IME for a custom View — this used to be left
        // to a Compose-level tap gesture wrapping this AndroidView, but that gesture never
        // actually fires because this onTouchEvent already consumes the event first, so the
        // keyboard silently failed to appear on tap. Showing it directly here, right where
        // the touch is actually handled, is what makes tapping the terminal reliable.
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
        return super.onTouchEvent(event) || true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!hasFocus()) requestFocus()
        return true
    }

    private fun handleSelectionTouch(event: android.view.MotionEvent) {
        val (row, col) = cellAt(event.x, event.y)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                selStartRow = row; selStartCol = col
                selEndRow = row; selEndCol = col
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                selEndRow = row; selEndCol = col
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                selEndRow = row; selEndCol = col
                onSelectionChanged?.invoke(hasSelection)
            }
        }
        postInvalidate()
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val col = if (charWidth > 0f) (x / charWidth).toInt().coerceIn(0, cols - 1) else 0
        val row = if (lineHeight > 0f) (y / lineHeight).toInt().coerceIn(0, rows - 1) else 0
        return row to col
    }

    /** Text between the drag's start and end cell, in on-screen reading order. */
    fun getSelectedText(): String? {
        if (!hasSelection) return null
        val em = emulator ?: return null
        val (fromRow, fromCol, toRow, toCol) = normalizedSelection()
        val snap = em.snapshot()
        val sb = StringBuilder()
        for (y in fromRow..toRow) {
            if (y >= snap.lines.size) break
            val line = snap.lines[y]
            val startX = if (y == fromRow) fromCol else 0
            val endX = if (y == toRow) toCol else line.size - 1
            for (x in startX..min(endX, line.size - 1)) sb.append(line[x].char)
            if (y != toRow) sb.append('\n')
        }
        // Trim trailing spaces bash/vim pad each row with, line by line.
        return sb.toString().lines().joinToString("\n") { it.trimEnd() }.trimEnd('\n')
    }

    private fun normalizedSelection(): SelectionBounds {
        val startFirst = selStartRow < selEndRow || (selStartRow == selEndRow && selStartCol <= selEndCol)
        return if (startFirst) SelectionBounds(selStartRow, selStartCol, selEndRow, selEndCol)
        else SelectionBounds(selEndRow, selEndCol, selStartRow, selStartCol)
    }

    private data class SelectionBounds(val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int)

    fun clearSelection() {
        selStartRow = -1; selStartCol = -1; selEndRow = -1; selEndCol = -1
        onSelectionChanged?.invoke(false)
        postInvalidate()
    }

    /** Copies the current selection to the clipboard. Returns true if there was something to copy. */
    fun copySelectionToClipboard(): Boolean {
        val text = getSelectedText()?.takeIf { it.isNotEmpty() } ?: return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        clearSelection()
        return true
    }

    /** Sends the clipboard's text content to the shell as if it had been typed. */
    fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) sendText(text)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, cursorPos: Int): Boolean {
                sendText(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, cursorPos: Int): Boolean = true

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                sendBytes(byteArrayOf(0x7f))
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                handleKey(event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleKey(event)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { sendBytes(byteArrayOf('\n'.code.toByte())); return true }
            KeyEvent.KEYCODE_DEL -> { sendBytes(byteArrayOf(0x7f)); return true }
            KeyEvent.KEYCODE_TAB -> { sendBytes(byteArrayOf('\t'.code.toByte())); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { sendKey("UP"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { sendKey("DOWN"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { sendKey("LEFT"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { sendKey("RIGHT"); return true }
            KeyEvent.KEYCODE_ESCAPE -> { sendKey("ESC"); return true }
        }
        val ch = event.unicodeChar
        if (ch != 0) {
            val bytes = emulator?.keyBytes(ch.toString(), ctrlHeld, altHeld)
                ?: ch.toString().toByteArray()
            consumeHeld()
            sendBytes(bytes)
            return true
        }
        return false
    }

    fun sendKey(name: String) {
        val em = emulator ?: return
        sendBytes(em.keyBytes(name, false, altHeld))
        consumeHeld()
    }

    fun sendText(text: String) {
        val em = emulator ?: return
        for (ch in text) {
            val bytes = em.keyBytes(ch.toString(), ctrlHeld, altHeld)
            consumeHeld()
            sendBytes(bytes)
        }
    }

    private fun consumeHeld() {
        if (ctrlHeld) ctrlHeld = false
        if (altHeld) altHeld = false
    }

    private fun sendBytes(bytes: ByteArray) {
        onBytes?.invoke(bytes)
        cursorOn = true
    }

    override fun onDraw(canvas: Canvas) {
        val em = emulator ?: return
        canvas.drawColor(defaultBg)
        val snap = em.snapshot()
        updatePaintMetrics()
        val runBuf = StringBuilder()
        var runFg = 0L
        var runStyle = 0

        for (y in 0 until minOf(rows, snap.lines.size)) {
            val top = y * lineHeight
            val line = snap.lines[y]
            // backgrounds
            var x = 0
            while (x < minOf(cols, line.size)) {
                val cell = line[x]
                if (cell.bg != TerminalCell.NO_COLOR) {
                    var end = x + 1
                    while (end < line.size && line[end].bg == cell.bg) end++
                    bgPaint.color = cell.bg.toInt()
                    canvas.drawRect(x * charWidth, top, end * charWidth, top + lineHeight, bgPaint)
                    x = end
                } else x++
            }
            // text runs
            runBuf.setLength(0)
            runFg = -1L
            runStyle = -1
            var runStart = 0
            for (cx in 0 until minOf(cols, line.size)) {
                val cell = line[cx]
                val inverse = cell.style and TerminalCell.STYLE_INVERSE != 0
                val fg = when {
                    cell.fg != TerminalCell.NO_COLOR -> cell.fg
                    else -> defaultFg.toLong() and 0xFFFFFFFFL
                }
                val bg = if (cell.bg != TerminalCell.NO_COLOR) cell.bg else defaultBg.toLong() and 0xFFFFFFFFL
                val effectiveFg = if (inverse) bg else fg
                if (effectiveFg != runFg || cell.style != runStyle) {
                    flushRun(canvas, runBuf, runFg, runStyle, y, runStart)
                    runFg = effectiveFg
                    runStyle = cell.style
                    runStart = cx
                }
                runBuf.append(cell.char)
            }
            flushRun(canvas, runBuf, runFg, runStyle, y, runStart)
        }

        if (hasSelection) drawSelection(canvas)

        if (snap.visible && cursorOn && isFocused) {
            cursorPaint.color = defaultFg
            cursorPaint.style = Paint.Style.STROKE
            cursorPaint.strokeWidth = 1.5f
            canvas.drawRect(
                snap.cursorX * charWidth, snap.cursorY * lineHeight,
                (snap.cursorX + 1) * charWidth, (snap.cursorY + 1) * lineHeight, cursorPaint,
            )
        }
    }

    private fun drawSelection(canvas: Canvas) {
        val (fromRow, fromCol, toRow, toCol) = normalizedSelection()
        for (y in max(fromRow, 0)..min(toRow, rows - 1)) {
            val startX = if (y == fromRow) fromCol else 0
            val endX = if (y == toRow) toCol else cols - 1
            canvas.drawRect(
                startX * charWidth, y * lineHeight,
                (endX + 1) * charWidth, (y + 1) * lineHeight, selectionPaint,
            )
        }
    }

    private fun flushRun(canvas: Canvas, buf: StringBuilder, fg: Long, style: Int, row: Int, x0: Int) {
        if (buf.isEmpty() || fg == -1L) return
        textPaint.color = if (fg == TerminalCell.NO_COLOR) defaultFg else fg.toInt()
        textPaint.isFakeBoldText = style and TerminalCell.STYLE_BOLD != 0
        textPaint.isUnderlineText = style and TerminalCell.STYLE_UNDERLINE != 0
        textPaint.alpha = if (style and TerminalCell.STYLE_DIM != 0) 160 else 255
        canvas.drawText(buf.toString(), x0 * charWidth, row * lineHeight + baseline, textPaint)
        buf.setLength(0)
    }
}
