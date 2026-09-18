package dev.ubuntu4a.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.ubuntu4a.app.Services
import dev.ubuntu4a.app.UbuntuSessionService
import dev.ubuntu4a.core.data.model.AppSettings
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.feature.appstore.AppStoreScreen
import dev.ubuntu4a.feature.desktop.DesktopScreen
import dev.ubuntu4a.feature.onboarding.OnboardingScreen
import dev.ubuntu4a.feature.terminal.TerminalScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val TERMINAL = "terminal/{id}"
    const val DESKTOP = "desktop/{id}"
    const val APPS = "apps/{id}"
    const val PACKAGES = "packages/{id}"
    fun terminal(id: String) = "terminal/$id"
    fun desktop(id: String) = "desktop/$id"
    fun apps(id: String) = "apps/$id"
    fun packages(id: String) = "packages/$id"
}

@Composable
fun Ubuntu4ANavHost(services: Services) {
    val nav = rememberNavController()
    val instances by services.instances.instances.collectAsState(initial = emptyList())
    val appSettings by services.settings.settings.collectAsState(initial = AppSettings())
    val activeIds by services.sessions.activeIds.collectAsState()

    fun instance(id: String): DistroInstance? = instances.firstOrNull { it.id == id }

    NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                services = services,
                instances = instances,
                activeIds = activeIds,
                onAdd = { nav.navigate(Routes.ONBOARDING) },
                onOpenTerminal = {
                    UbuntuSessionService.start(services.appContext)
                    nav.navigate(Routes.terminal(it))
                },
                onOpenDesktop = {
                    UbuntuSessionService.start(services.appContext)
                    nav.navigate(Routes.desktop(it))
                },
                onOpenApps = { nav.navigate(Routes.apps(it)) },
                onOpenPackages = { nav.navigate(Routes.packages(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                orchestrator = services.setup,
                newInstanceId = { "i" + System.currentTimeMillis().toString(36) },
                onProvisioned = { inst, _ -> services.instances.upsert(inst) },
                rootfsSource = appSettings.rootfsSource,
                onSourceChange = { src -> services.settings.update { it.copy(rootfsSource = src) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                services = services,
                settings = appSettings,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.TERMINAL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val inst = instance(id)
            if (inst == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                TerminalScreen(
                    instance = inst,
                    sessions = services.sessions,
                    settings = appSettings,
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable(Routes.DESKTOP, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val inst = instance(id)
            if (inst == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                DesktopScreen(
                    instance = inst,
                    vnc = services.vnc,
                    settings = appSettings,
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable(Routes.APPS, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val inst = instance(id)
            if (inst == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                AppStoreScreen(
                    instance = inst,
                    runner = services.commandRunner,
                    apt = services.apt,
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable(Routes.PACKAGES, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val inst = instance(id)
            if (inst == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                PackagesScreen(
                    instance = inst,
                    apt = services.apt,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
