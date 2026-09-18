package dev.ubuntu4a.app

import android.content.Context
import dev.ubuntu4a.core.data.repo.InstanceRepository
import dev.ubuntu4a.core.data.repo.SettingsRepository
import dev.ubuntu4a.core.proot.AptRunner
import dev.ubuntu4a.core.proot.BinaryProvider
import dev.ubuntu4a.core.proot.CommandRunner
import dev.ubuntu4a.core.proot.InstanceManager
import dev.ubuntu4a.core.proot.SessionManager
import dev.ubuntu4a.core.proot.setup.AudioBridge
import dev.ubuntu4a.core.proot.setup.SetupOrchestrator
import dev.ubuntu4a.core.rootfs.RootfsDownloader
import dev.ubuntu4a.core.rootfs.RootfsExtractor
import dev.ubuntu4a.core.vnc.VncController

/** Manual DI graph — one instance for the whole process. */
class Services(val appContext: Context) {
    val settings = SettingsRepository(appContext)
    val instances = InstanceRepository(appContext)
    val binaries = BinaryProvider(appContext)
    val commandRunner = CommandRunner(appContext, binaries, settings)
    val apt = AptRunner(commandRunner)
    val sessions = SessionManager(appContext, binaries, settings)
    val audio = AudioBridge(appContext, binaries, commandRunner)
    val setup = SetupOrchestrator(
        context = appContext,
        binaries = binaries,
        settings = settings,
        downloader = RootfsDownloader(),
        extractor = RootfsExtractor(),
        runner = commandRunner,
        audio = audio,
    )
    val manager = InstanceManager(appContext, commandRunner)
    val vnc = VncController(commandRunner)
}
