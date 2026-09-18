package dev.ubuntu4a.feature.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ubuntu4a.core.data.catalog.UbuntuCatalog
import dev.ubuntu4a.core.data.model.DesktopEnv
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.data.model.ReleaseStatus
import dev.ubuntu4a.core.data.model.RootfsSource
import dev.ubuntu4a.core.data.model.SetupProgress
import dev.ubuntu4a.core.data.model.UbuntuRelease
import dev.ubuntu4a.core.data.util.ArchDetector
import dev.ubuntu4a.core.proot.setup.InstallRequest
import dev.ubuntu4a.core.proot.setup.SetupOrchestrator
import dev.ubuntu4a.core.ui.components.BrandHero
import dev.ubuntu4a.core.ui.components.LogConsole
import dev.ubuntu4a.core.ui.components.PressableCard
import dev.ubuntu4a.core.ui.theme.UbuntuMotion
import kotlinx.coroutines.launch

private const val STEP_WELCOME = 0
private const val STEP_RELEASE = 1
private const val STEP_DE = 2
private const val STEP_CREDS = 3
private const val STEP_INSTALL = 4

/**
 * Setup wizard that replaces every interactive prompt of ubuntu.sh
 * ("Select your ubuntu <code name>", "Input username", "Input password")
 * with Material 3 Expressive screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    orchestrator: SetupOrchestrator,
    newInstanceId: () -> String,
    onProvisioned: suspend (DistroInstance, InstallRequest) -> Unit,
    rootfsSource: RootfsSource,
    onSourceChange: (RootfsSource) -> Unit,
    onBack: () -> Unit,
) {
    var step by remember { mutableStateOf(STEP_WELCOME) }
    var release by remember { mutableStateOf(UbuntuCatalog.releases.first { it.codeName == "plucky" }) }
    var desktop by remember { mutableStateOf(DesktopEnv.NONE) }
    var username by remember { mutableStateOf("ubuntu") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var localArchive by remember { mutableStateOf<Uri?>(null) }
    var useLocal by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<SetupProgress?>(null) }
    var percent by remember { mutableFloatStateOf(-1f) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { localArchive = it; useLocal = true }
    }

    val usernameError = remember(username) { SetupOrchestrator.validateUsername(username) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (step == STEP_INSTALL) "Installing" else "Setup Ubuntu") },
                navigationIcon = {
                    IconButton(onClick = { if (step == STEP_WELCOME) onBack() else step-- }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally(ubuntuSlideSpec()) + fadeIn()) togetherWith
                    (slideOutHorizontally(ubuntuSlideSpec()) { -it } + fadeOut(tween(120)))
            },
            label = "onboarding",
        ) { current ->
            Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                when (current) {
                    STEP_WELCOME -> WelcomePage(
                        onContinue = { step = STEP_RELEASE },
                        onImport = {
                            useLocal = true
                            filePicker.launch(arrayOf("*/*"))
                            step = STEP_RELEASE
                        },
                    )
                    STEP_RELEASE -> ReleasePage(
                        selected = release,
                        onSelect = { release = it },
                        useLocal = useLocal,
                        localFile = localArchive?.lastPathSegment,
                        onPickLocal = {
                            if (useLocal) {
                                useLocal = false
                                localArchive = null
                            } else {
                                filePicker.launch(arrayOf("*/*"))
                            }
                        },
                        rootfsSource = rootfsSource,
                        onSourceChange = onSourceChange,
                        onNext = { step = STEP_DE },
                    )
                    STEP_DE -> DePage(
                        selected = desktop,
                        onSelect = { desktop = it },
                        onNext = { step = STEP_CREDS },
                    )
                    STEP_CREDS -> CredentialsPage(
                        username = username,
                        password = password,
                        usernameError = usernameError,
                        onUsername = { username = it },
                        onPassword = { password = it },
                        onToggleShow = { showPassword = !showPassword },
                        showPassword = showPassword,
                        canInstall = usernameError == null && password.length >= 4,
                        onBack = { step = STEP_DE },
                        onInstall = {
                            error = null
                            logs = emptyList()
                            percent = -1f
                            busy = true
                            step = STEP_INSTALL
                            val id = newInstanceId()
                            val req = InstallRequest(
                                codeName = release.codeName,
                                version = release.version,
                                arch = ArchDetector.current.rootfsArch,
                                username = username,
                                password = password,
                                desktopEnv = desktop,
                                localArchive = if (useLocal) localArchive else null,
                            )
                            val inst = DistroInstance(
                                id = id,
                                codeName = release.codeName,
                                version = release.version,
                                arch = req.arch,
                                username = username,
                                desktopEnv = desktop,
                                createdAt = System.currentTimeMillis(),
                            )
                            scope.launch {
                                orchestrator.install(req, inst).collect { event ->
                                    progress = event
                                    when (event) {
                                        is SetupProgress.Log -> logs = (logs + event.line).takeLast(400)
                                        is SetupProgress.Percent -> percent = event.fraction
                                        is SetupProgress.Step -> {
                                            percent = -1f
                                            logs = (logs + "-- ${event.label} ${event.detail ?: ""}".trim()).takeLast(400)
                                        }
                                        is SetupProgress.Error -> {
                                            error = event.message + (event.cause?.let { " ($it)" } ?: "")
                                            busy = false
                                        }
                                        SetupProgress.Done -> {
                                            busy = false
                                            percent = 1f
                                            onProvisioned(inst, req)
                                            onBack()
                                        }
                                    }
                                }
                            }
                        },
                    )
                    STEP_INSTALL -> InstallPage(
                        busy = busy,
                        percent = percent,
                        progress = progress,
                        logs = logs,
                        error = error,
                        onRetry = { step = STEP_CREDS },
                    )
                }
            }
        }
    }
}

private fun ubuntuSlideSpec() =
    androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
    )

@Composable
private fun WelcomePage(onContinue: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        BrandHero(
            title = "Ubuntu for Android",
            subtitle = "Full Ubuntu, right inside the app - no Termux required.",
        )
        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp)) {
                Text("What happens next", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                listOf(
                    "Pick an Ubuntu release (focal -> questing)",
                    "Optionally install XFCE / LXQt / LXDE / KDE",
                    "Create your username + password",
                    "The rootfs is downloaded, extracted and provisioned automatically",
                ).forEach {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("*", Modifier.padding(end = 8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Get started")
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Rounded.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Install from a local rootfs file (.tar.gz / .tar.xz)")
        }
    }
}

@Composable
private fun ReleasePage(
    selected: UbuntuRelease,
    onSelect: (UbuntuRelease) -> Unit,
    useLocal: Boolean,
    localFile: String?,
    onPickLocal: () -> Unit,
    rootfsSource: RootfsSource,
    onSourceChange: (RootfsSource) -> Unit,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Detected architecture: ${ArchDetector.current.rootfsArch} (${ArchDetector.current.androidAbi})",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            FilterChip(
                selected = rootfsSource == RootfsSource.RELEASE && !useLocal,
                onClick = { onSourceChange(RootfsSource.RELEASE) },
                label = { Text("wahasa/Ubuntu Rootfs release") },
            )
            FilterChip(
                selected = rootfsSource == RootfsSource.OCI && !useLocal,
                onClick = { onSourceChange(RootfsSource.OCI) },
                label = { Text("Canonical OCI") },
            )
        }
        Text(
            "GitHub release = prebuilt .tar.xz with pinned SHA-256 (plucky ~50 MB). Releases without an asset (focal/bionic) automatically fall back to Canonical OCI.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(UbuntuCatalog.releases, key = { it.codeName }) { r ->
                val badge = when (r.status) {
                    ReleaseStatus.STABLE -> if (r.lts) "LTS" else "stable"
                    ReleaseStatus.EXPERIMENTAL -> "next"
                    ReleaseStatus.DEVEL -> "devel"
                }
                val fromRelease = rootfsSource == RootfsSource.RELEASE &&
                    UbuntuCatalog.hasReleaseAsset(r.codeName, ArchDetector.current.rootfsArch)
                PressableCard(onClick = { onSelect(r) }) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${r.codeName}  ${r.version}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                r.label + if (fromRelease) "  · wahasa, sha256-pinned" else "  · via OCI",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (r == selected) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (r.status == ReleaseStatus.STABLE) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                badge,
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            item {
                PressableCard(onClick = onPickLocal) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderOpen, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (useLocal) "Local rootfs: ON" else "Use local rootfs file", style = MaterialTheme.typography.titleMedium)
                            Text(localFile ?: "e.g. ubuntu-plucky-arm64-root.tar.xz", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue") }
    }
}

@Composable
private fun DePage(selected: DesktopEnv, onSelect: (DesktopEnv) -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Desktop environment", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(DesktopEnv.entries.toList(), key = { it.name }) { de ->
                PressableCard(onClick = { onSelect(de) }) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(de.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(UbuntuCatalog.deDescription(de), style = MaterialTheme.typography.bodySmall)
                        }
                        if (de == selected) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue") }
    }
}

@Composable
private fun CredentialsPage(
    username: String,
    password: String,
    usernameError: String?,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onToggleShow: () -> Unit,
    canInstall: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    showPassword: Boolean,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("Create your user", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
        OutlinedTextField(
            value = username,
            onValueChange = onUsername,
            label = { Text("Username") },
            singleLine = true,
            isError = usernameError != null,
            supportingText = { usernameError?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            supportingText = { if (password.length < 4) Text("min 4 characters") },
            trailingIcon = { TextButton(onClick = onToggleShow) { Text(if (showPassword) "HIDE" else "SHOW") } },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Replaces `set user / set pw` from ubuntu.sh. Used once for chpasswd and vncpasswd, never stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.height(52.dp)) { Text("Back") }
            FilledTonalButton(
                onClick = onInstall,
                enabled = canInstall,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Rounded.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text("Install Ubuntu")
            }
        }
    }
}

@Composable
private fun InstallPage(
    busy: Boolean,
    percent: Float,
    progress: SetupProgress?,
    logs: List<String>,
    error: String?,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Setup failed", style = MaterialTheme.typography.titleMedium)
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRetry) { Text("Go back") }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (percent in 0f..1f && percent > 0f) {
                    LinearProgressIndicator(
                        progress = { percent },
                        modifier = Modifier.weight(1f).height(8.dp),
                        strokeCap = StrokeCap.Round,
                    )
                    Text(
                        " ${(percent * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (val p = progress) {
                            is SetupProgress.Step -> p.label
                            is SetupProgress.Percent -> p.label
                            SetupProgress.Done -> "Done"
                            else -> "Working..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LogConsole(
                lines = logs,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}
