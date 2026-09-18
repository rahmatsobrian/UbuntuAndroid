package dev.ubuntu4a.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.os.Build
import dev.ubuntu4a.app.Services
import dev.ubuntu4a.core.data.model.AppSettings
import dev.ubuntu4a.core.data.model.ThemeMode
import dev.ubuntu4a.core.proot.NativeChroot
import dev.ubuntu4a.core.proot.setup.AudioMode
import dev.ubuntu4a.core.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    services: Services,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    var rooted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { rooted = NativeChroot.hasRoot() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionHeader("Appearance")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Theme", Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { services.settings.update { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) {
                            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Dynamic color (Material You)", Modifier.weight(1f))
                Switch(
                    checked = settings.dynamicColor && Build.VERSION.SDK_INT >= 31,
                    enabled = Build.VERSION.SDK_INT >= 31,
                    onCheckedChange = { services.settings.update { it.copy(dynamicColor = !it.dynamicColor) } },
                )
            }
            if (Build.VERSION.SDK_INT < 31) {
                Text(
                    "Requires Android 12+. Ubuntu brand palette is used instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Terminal")
            Text("Font size: ${settings.terminalFontSizeSp.toInt()}sp")
            Slider(
                value = settings.terminalFontSizeSp,
                onValueChange = { v -> services.settings.update { it.copy(terminalFontSizeSp = v) } },
                valueRange = 8f..24f,
            )

            SectionHeader("Desktop / VNC")
            Text("Resolution")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(
                    value = settings.desktopWidth.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { w -> services.settings.update { it.copy(desktopWidth = w) } } },
                    label = { Text("Width") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = settings.desktopHeight.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { h -> services.settings.update { it.copy(desktopHeight = h) } } },
                    label = { Text("Height") },
                    modifier = Modifier.weight(1f),
                )
            }

            SectionHeader("System")
            Text("Rootfs source", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = settings.rootfsSource == dev.ubuntu4a.core.data.model.RootfsSource.RELEASE,
                    onClick = { services.settings.update { it.copy(rootfsSource = dev.ubuntu4a.core.data.model.RootfsSource.RELEASE) } },
                    label = { Text("wahasa Rootfs") },
                )
                androidx.compose.material3.FilterChip(
                    selected = settings.rootfsSource == dev.ubuntu4a.core.data.model.RootfsSource.OCI,
                    onClick = { services.settings.update { it.copy(rootfsSource = dev.ubuntu4a.core.data.model.RootfsSource.OCI) } },
                    label = { Text("Canonical OCI") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Audio bridge", Modifier.weight(1f))
                Text(
                    when (services.audio.mode) {
                        AudioMode.HOST_PULSE_AAUDIO -> "host pulseaudio (AAudio)"
                        AudioMode.CONTAINER_PULSE -> "container pulseaudio (TCP)"
                        AudioMode.NONE -> "inactive"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable audio on setup", Modifier.weight(1f))
                Switch(
                    checked = settings.audioEnabled,
                    onCheckedChange = { checked -> services.settings.update { it.copy(audioEnabled = checked) } },
                )
            }
            OutlinedTextField(
                value = settings.kernelReleaseSpoof,
                onValueChange = { v -> services.settings.update { it.copy(kernelReleaseSpoof = v) } },
                label = { Text("Spoofed kernel release (--kernel-release)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = settings.rootfsMirror,
                onValueChange = { v -> services.settings.update { it.copy(rootfsMirror = v.trim()) } },
                label = { Text("Rootfs mirror base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            rooted?.let { r ->
                SectionHeader("Root (experimental)")
                Text(if (r) "su root available: native chroot path unlocked" else "No root: PRoot is used for everything")
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Text(
                "Ubuntu for Android - containers live under files/containers/. " +
                    "proot and PulseAudio are GPL; see README for license files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
            )
        }
    }
}
