package dev.ubuntu4a.feature.terminal

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ubuntu4a.core.data.model.AppSettings
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.proot.SessionManager
import kotlinx.coroutines.launch

private val TermBg = Color(0xFF0C0C0C)

@Composable
fun TerminalScreen(
    instance: DistroInstance,
    sessions: SessionManager,
    settings: AppSettings,
    asRoot: Boolean = false,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { TerminalEngine() }
    val viewHolder = remember { arrayOfNulls<TerminalView>(1) }
    var ctrlHeld by remember { mutableStateOf(false) }
    var altHeld by remember { mutableStateOf(false) }
    var imeVisible by remember { mutableStateOf(true) }
    var selectionMode by remember { mutableStateOf(false) }
    var hasSelection by remember { mutableStateOf(false) }

    DisposableEffect(instance.id) {
        val proc = sessions.openTerminal(instance, 80, 24, asRoot)
        engine.attach(proc)
        val collector = scope.launch {
            proc.output.collect { engine.feed(it) }
        }
        onDispose {
            collector.cancel()
            engine.detach()
            // Never let teardown (killing the proot process, native pty reap, …) crash the
            // app when the screen is left — worst case the session leaks and gets reaped
            // next time this instance is opened, instead of an unhandled exception taking
            // the whole app down a moment after the user has already navigated away.
            runCatching { sessions.close(instance.id) }
        }
    }

    Surface(color = TermBg, modifier = Modifier.fillMaxSize()) {
        // statusBarsPadding() keeps the first terminal row from being drawn underneath the
        // Android status bar — without it the emulator's canvas starts at y=0 and the top
        // line of text (and the cursor, right after boot) is hidden behind the clock/icons.
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx).also { tv ->
                            viewHolder[0] = tv
                            tv.emulator = engine.emulator
                            tv.defaultBg = 0xFF0C0C0C.toInt()
                            tv.defaultFg = 0xFFE6E6E6.toInt()
                            tv.fontSizePx = settings.terminalFontSizeSp * ctx.resources.displayMetrics.scaledDensity
                            tv.onBytes = { bytes -> engine.write(bytes) }
                            engine.onInvalidate = { tv.postInvalidate() }
                            // Tapping to focus + pop the keyboard, and dragging to select text,
                            // both need real touch coordinates and to know whether the view is
                            // in selection mode — that only works from inside the View's own
                            // onTouchEvent (see TerminalView), so nothing is wired up here.
                            tv.onSelectionChanged = { hasSelection = it }
                            tv.requestFocus()
                        }
                    },
                    update = { tv -> tv.selectionMode = selectionMode },
                    onRelease = { tv -> viewHolder[0] = null },
                )
            }
            ExtraKeysRow(
                selectionMode = selectionMode,
                hasSelection = hasSelection,
                ctrlHeld = ctrlHeld,
                altHeld = altHeld,
                onKey = { key ->
                    when (key) {
                        "CTRL" -> ctrlHeld = !ctrlHeld
                        "ALT" -> altHeld = !altHeld
                        "IME" -> {
                            imeVisible = !imeVisible
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            viewHolder[0]?.let {
                                if (imeVisible) imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
                                else imm.hideSoftInputFromWindow(it.windowToken, 0)
                            }
                        }
                        "SEL" -> {
                            // Toggling off drops whatever was highlighted too, so the key
                            // doubles as a "cancel selection" action.
                            selectionMode = !selectionMode
                            if (!selectionMode) viewHolder[0]?.clearSelection()
                        }
                        "COPY" -> {
                            viewHolder[0]?.copySelectionToClipboard()
                            selectionMode = false
                        }
                        "PASTE" -> viewHolder[0]?.pasteFromClipboard()
                        else -> {
                            val tv = viewHolder[0]
                            tv?.ctrlHeld = ctrlHeld
                            tv?.altHeld = altHeld
                            tv?.sendKey(key)
                            if (ctrlHeld) ctrlHeld = false
                            if (altHeld) altHeld = false
                        }
                    }
                },
                onText = { text ->
                    val tv = viewHolder[0]
                    tv?.ctrlHeld = ctrlHeld
                    tv?.altHeld = altHeld
                    tv?.sendText(text)
                },
            )
        }
    }
}

@Composable
private fun ExtraKeysRow(
    selectionMode: Boolean,
    hasSelection: Boolean,
    ctrlHeld: Boolean,
    altHeld: Boolean,
    onKey: (String) -> Unit,
    onText: (String) -> Unit,
) {
    // A fixed-width Row with every key at equal .weight(1f) squeezed 12 keys into whatever
    // width the screen had, so "CTRL"/"ALT" got crushed onto two lines while "|" sat in a
    // needlessly huge box next to it — that unevenness is what read as "weird". Giving each
    // key its own natural width (short keys narrow, label keys wider) and letting the whole
    // row scroll horizontally instead fixes both: nothing gets crushed, and there's now
    // headroom to add more keys (SEL/COPY/PASTE) without re-cramping the existing ones.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .height(56.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Key("ESC", { onKey("ESC") })
        Key("TAB", { onKey("TAB") })
        Key("CTRL", { onKey("CTRL") }, active = ctrlHeld, wide = true)
        Key("ALT", { onKey("ALT") }, active = altHeld, wide = true)
        Key("↑", { onKey("UP") })
        Key("↓", { onKey("DOWN") })
        Key("←", { onKey("LEFT") })
        Key("→", { onKey("RIGHT") })
        Key("|", { onText("|") })
        Key("-", { onText("-") })
        Key("/", { onText("/") })
        Key("SEL", { onKey("SEL") }, active = selectionMode, wide = true)
        Key("COPY", { onKey("COPY") }, active = hasSelection, wide = true)
        Key("PASTE", { onKey("PASTE") }, wide = true)
        Key("⌨", { onKey("IME") })
    }
}

@Composable
private fun Key(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    wide: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = if (wide) 56.dp else 44.dp)
            .background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(8.dp),
            )
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            maxLines = 1,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
