package dev.ubuntu4a.feature.desktop

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ubuntu4a.core.data.model.AppSettings
import dev.ubuntu4a.core.data.model.DesktopEnv
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.data.model.SetupProgress
import dev.ubuntu4a.core.vnc.VncController
import dev.ubuntu4a.core.vnc.VncState
import kotlinx.coroutines.launch
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DesktopScreen(
    instance: DistroInstance,
    vnc: VncController,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by vnc.state.collectAsState()
    var stage by remember { mutableStateOf("idle") }
    var error by remember { mutableStateOf<String?>(null) }
    var pageUrl by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }

    fun startDesktop() {
        if (instance.desktopEnv == DesktopEnv.NONE) {
            error = "This instance has no desktop installed. Use the Terminal."
            return
        }
        scope.launch {
            stage = "starting"
            error = null
            val pw = "u" + Random.nextBytes(5).joinToString("") { "%02x".format(it) }
            vnc.start(instance, "${settings.desktopWidth}x${settings.desktopHeight}", pw).collect { ev ->
                when (ev) {
                    is SetupProgress.Done -> {
                        stage = "connected"
                        pageUrl = buildNovncUrl(vnc.bridgeUrl(), pw)
                    }
                    is SetupProgress.Error -> {
                        stage = "idle"
                        error = ev.message + (ev.cause?.let { "\n$it" } ?: "")
                    }
                    else -> {}
                }
            }
        }
    }

    LaunchedEffect(instance.id) { if (state != VncState.RUNNING) startDesktop() }

    Scaffold(
        topBar = {
            if (!fullscreen) {
                TopAppBar(
                    title = { Text("Desktop · ${instance.codeName}") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
                    },
                    actions = {
                        IconButton(onClick = { reloadToken++ }) { Icon(Icons.Rounded.Refresh, "Reload") }
                        IconButton(onClick = { fullscreen = true }) { Icon(Icons.Rounded.Fullscreen, "Fullscreen") }
                        IconButton(onClick = {
                            scope.launch {
                                vnc.stop().collect { }
                                stage = "idle"
                                pageUrl = null
                            }
                        }) { Icon(Icons.Rounded.Stop, "Stop VNC") }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val url = pageUrl
            when {
                url != null -> AndroidView(
                    modifier = Modifier.fillMaxSize()
                        .then(if (fullscreen) Modifier else Modifier.padding(8.dp)),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            this.settings.javaScriptEnabled = true
                            this.settings.allowFileAccess = true
                            this.settings.allowContentAccess = true
                            this.settings.builtInZoomControls = false
                            setBackgroundColor(android.graphics.Color.BLACK)
                            webViewClient = WebViewClient()
                        }
                    },
                    update = { web ->
                        if (web.url != url || reloadToken > 0) {
                            web.loadUrl(url)
                        }
                    },
                    onRelease = { it.destroy() },
                )
                state == VncState.RUNNING && stage == "connected" -> {}
                stage == "starting" -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Starting TigerVNC inside the container…", style = MaterialTheme.typography.bodyMedium)
                }
                else -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No desktop session", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { startDesktop() }) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.height(0.dp))
                            Text(" Start desktop")
                        }
                        if (fullscreen) OutlinedButton(onClick = { fullscreen = false }) { Text("Exit") }
                    }
                }
            }
            if (fullscreen) {
                Surface(
                    tonalElevation = 3.dp,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    TextButton(onClick = { fullscreen = false }) { Text("Exit fullscreen") }
                }
            }
        }
    }
}

private fun buildNovncUrl(bridgeUrl: String, password: String): String {
    // ws://127.0.0.1:6080/websockify → host / port / path params for noVNC
    val noScheme = bridgeUrl.substringAfter("://")
    val hostPort = noScheme.substringBefore('/')
    val host = hostPort.substringBefore(':')
    val port = hostPort.substringAfter(':')
    return "file:///android_asset/novnc/vnc_lite.html" +
        "?autoconnect=true&host=$host&port=$port&path=websockify" +
        "&password=$password&resize=scale&view_only=false"
}
