package dev.ubuntu4a.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.proot.AptEvent
import dev.ubuntu4a.core.proot.AptRunner
import dev.ubuntu4a.core.ui.components.LogConsole
import dev.ubuntu4a.core.ui.components.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(
    instance: DistroInstance,
    apt: AptRunner,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<dev.ubuntu4a.core.proot.AptPackage>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf(listOf<String>()) }
    val sheetState = rememberModalBottomSheetState()

    fun runApt(title: String, args: List<String>) {
        busy = title
        logLines = emptyList()
        showLog = true
        scope.launch {
            apt.run(instance, args).collect { ev ->
                when (ev) {
                    is AptEvent.Line -> logLines = (logLines + ev.text).takeLast(400)
                    is AptEvent.Done -> busy = null
                    is AptEvent.Fail -> {
                        logLines = logLines + "E/ exit ${ev.code}"
                        busy = null
                    }
                }
            }
            installed = apt.installedPackages(instance)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Packages · ${instance.codeName}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                actions = {
                    FilledTonalButton(
                        onClick = { runApt("update", listOf("update")) },
                        enabled = busy == null,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("update") }
                    FilledTonalButton(
                        onClick = { runApt("upgrade", listOf("upgrade", "-y")) },
                        enabled = busy == null,
                        modifier = Modifier.padding(end = 12.dp),
                    ) { Text("upgrade") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("apt search…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(onClick = {
                    if (query.isNotBlank()) {
                        searching = true
                        scope.launch {
                            results = apt.search(instance, query)
                            installed = apt.installedPackages(instance)
                            searching = false
                        }
                    }
                }) {
                    if (searching) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Search, "Search")
                }
            }
            SectionHeader(if (results.isEmpty()) "Search from the field above" else "${results.size} packages")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results, key = { it.name }) { pkg ->
                    ListItem(
                        headlineContent = { Text(pkg.name) },
                        supportingContent = {
                            Column {
                                Text(pkg.version)
                                Text(pkg.description, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (pkg.name in installed) {
                                    IconButton(
                                        onClick = { runApt("remove ${pkg.name}", listOf("remove", "-y", pkg.name)) },
                                        enabled = busy == null,
                                    ) { Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                                } else {
                                    Button(
                                        onClick = { runApt("install ${pkg.name}", listOf("install", "-y", pkg.name)) },
                                        enabled = busy == null,
                                    ) {
                                        Icon(Icons.Rounded.Download, null)
                                        Spacer(Modifier.height(0.dp))
                                        Text("Install")
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        if (showLog) {
            ModalBottomSheet(onDismissRequest = { showLog = false }, sheetState = sheetState) {
                Column(Modifier.fillMaxWidth().height(320.dp)) {
                    Text("apt log · ${busy ?: "done"}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    LogConsole(lines = logLines, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
