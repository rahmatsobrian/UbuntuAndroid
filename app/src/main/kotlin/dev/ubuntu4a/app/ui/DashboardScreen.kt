package dev.ubuntu4a.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ubuntu4a.app.Services
import dev.ubuntu4a.core.data.InstancePaths
import dev.ubuntu4a.core.data.model.DesktopEnv
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.ui.components.ConfirmDialog
import dev.ubuntu4a.core.ui.components.PressableCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    services: Services,
    instances: List<DistroInstance>,
    activeIds: Set<String>,
    onAdd: () -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenDesktop: (String) -> Unit,
    onOpenApps: (String) -> Unit,
    onOpenPackages: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<DistroInstance?>(null) }
    var busyDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ubuntu for Android", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Install Ubuntu") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (instances.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.Android,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(72.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No Ubuntu yet", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Download a rootfs and get a full Ubuntu with terminal, desktop and app store.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    ExtendedFloatingActionButton(onClick = onAdd) { Text("Install Ubuntu") }
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(instances, key = { it.id }) { inst ->
                        InstanceCard(
                            instance = inst,
                            running = inst.id in activeIds,
                            onTerminal = { onOpenTerminal(inst.id) },
                            onDesktop = { onOpenDesktop(inst.id) },
                            onApps = { onOpenApps(inst.id) },
                            onPackages = { onOpenPackages(inst.id) },
                            onStop = { services.sessions.close(inst.id) },
                            onBackup = {
                                scope.launch {
                                    services.manager.backup(inst, inst.id).collect { prog ->
                                        if (prog is dev.ubuntu4a.core.data.model.SetupProgress.Done) {
                                            android.widget.Toast.makeText(
                                                services.appContext,
                                                "Backup saved under files/backups/",
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }
                            },
                            onDelete = { confirmDelete = inst },
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { inst ->
        ConfirmDialog(
            title = "Delete ${inst.codeName}?",
            message = "This removes the whole rootfs (${inst.username}). This cannot be undone.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                if (!busyDelete) {
                    busyDelete = true
                    scope.launch {
                        services.sessions.close(inst.id)
                        withContext(Dispatchers.IO) {
                            InstancePaths.containerDir(services.appContext, inst.id).deleteRecursively()
                        }
                        services.instances.remove(inst.id)
                        busyDelete = false
                        confirmDelete = null
                    }
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun InstanceCard(
    instance: DistroInstance,
    running: Boolean,
    onTerminal: () -> Unit,
    onDesktop: () -> Unit,
    onApps: () -> Unit,
    onPackages: () -> Unit,
    onStop: () -> Unit,
    onBackup: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Ubuntu ${instance.version}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${instance.codeName} · ${instance.arch} · user ${instance.username}" +
                            (if (instance.desktopEnv != DesktopEnv.NONE) " · ${instance.desktopEnv.displayName}" else " · CLI"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        if (running) "● running" else "○ stopped",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                // The menu button and its DropdownMenu must share a Box: DropdownMenu is a
                // Popup that anchors to the composable it's declared next to. Previously the
                // menu lived at the very bottom of the whole screen, so it always popped up
                // far away from the "⋮" button instead of right under it.
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Backup rootfs (tar.gz)") },
                            onClick = {
                                menuExpanded = false
                                onBackup()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    PressableCard(onClick = onStop, modifier = Modifier.size(48.dp)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("■", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                ActionTile("Terminal", Icons.Rounded.Terminal, onTerminal)
                ActionTile("Desktop", Icons.Rounded.DesktopWindows, onDesktop)
                ActionTile("Apps", Icons.Rounded.Apps, onApps)
                ActionTile("Apt", Icons.Rounded.Memory, onPackages)
            }
        }
    }
}

@Composable
private fun ActionTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    PressableCard(onClick = onClick, modifier = Modifier.width(72.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
