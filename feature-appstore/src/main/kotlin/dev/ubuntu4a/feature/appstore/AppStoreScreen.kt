package dev.ubuntu4a.feature.appstore

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ubuntu4a.core.data.catalog.LinuxApp
import dev.ubuntu4a.core.data.catalog.LinuxAppCatalog
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.proot.AptRunner
import dev.ubuntu4a.core.proot.CommandRunner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppStoreScreen(
    instance: DistroInstance,
    runner: CommandRunner,
    apt: AptRunner,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var category by remember { mutableStateOf("All") }
    var busyApp by remember { mutableStateOf<String?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var sheetLines by remember { mutableStateOf(listOf<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(instance.id) {
        installed = apt.installedPackages(instance)
    }

    val categories = remember { listOf("All") + LinuxAppCatalog.apps.map { it.category }.distinct() }
    val apps = remember(category) {
        LinuxAppCatalog.apps.filter { category == "All" || it.category == category }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Linux apps · ${instance.codeName}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                categories.forEach { c ->
                    FilterChip(
                        selected = c == category,
                        onClick = { category = c },
                        label = { Text(c, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            message?.let {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(apps, key = { it.id }) { app ->
                    AppCard(
                        app = app,
                        installed = app.packages.all { it.substringBefore(':') in installed || it in installed },
                        busy = busyApp == app.id,
                        onInstall = {
                            busyApp = app.id
                            message = null
                            scope.launch {
                                sheetLines = emptyList()
                                showSheet = true
                                apt.run(instance, listOf("install", "-y") + app.packages).collect { ev ->
                                    when (ev) {
                                        is dev.ubuntu4a.core.proot.AptEvent.Line ->
                                            sheetLines = (sheetLines + ev.text).takeLast(400)
                                        is dev.ubuntu4a.core.proot.AptEvent.Done -> {
                                            AppFixes.apply(context, instance, app.id, runner)
                                            installed = apt.installedPackages(instance)
                                            showSheet = false
                                            busyApp = null
                                            message = "${app.name} installed ✓"
                                        }
                                        is dev.ubuntu4a.core.proot.AptEvent.Fail -> {
                                            showSheet = false
                                            busyApp = null
                                            message = "install of ${app.name} failed (see log)"
                                        }
                                    }
                                }
                            }
                        },
                        onLaunch = {
                            busyApp = app.id
                            scope.launch {
                                val cmd = "su - ${instance.username} -c 'export DISPLAY=:1 PULSE_SERVER=127.0.0.1; " +
                                    "nohup ${app.launchCmd} >/dev/null 2>&1 &'"
                                val args = runner.buildArgs(instance, cmd).toMutableList()
                                args.remove("--kill-on-exit")
                                runCatching {
                                    val p = ProcessBuilder(args).redirectErrorStream(true).start()
                                    p.waitFor()
                                }
                                busyApp = null
                                message = "${app.name} launched — open the Desktop screen"
                            }
                        },
                    )
                }
            }
        }
        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
                Column(Modifier.fillMaxWidth().height(320.dp)) {
                    Text("apt log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    dev.ubuntu4a.core.ui.components.LogConsole(
                        lines = sheetLines,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun AppCard(
    app: LinuxApp,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember(app.id) {
        runCatching {
            context.assets.open("appicons/${app.id}.jpg").use {
                android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap()
            }
        }.getOrNull()
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (installed) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                app.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (installed) {
                    SmallFloatingActionButton(onClick = onLaunch, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Rounded.PlayArrow, "Launch")
                    }
                    TextButton(onClick = onInstall) { Text("Repair") }
                } else {
                    SmallFloatingActionButton(onClick = onInstall) {
                        if (busy) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Download, "Install")
                        }
                    }
                }
            }
        }
    }
}
