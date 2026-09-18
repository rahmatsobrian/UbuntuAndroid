package dev.ubuntu4a.core.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ubuntu4a.core.data.model.BindEntry
import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.data.model.RootfsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "ubuntu4a")

class InstanceRepository(private val context: Context) {
    private val key = stringPreferencesKey("instances")
    private val json = Json { ignoreUnknownKeys = true }

    val instances: Flow<List<DistroInstance>> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<List<DistroInstance>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
    }

    suspend fun upsert(instance: DistroInstance) = edit { list ->
        val index = list.indexOfFirst { it.id == instance.id }
        if (index >= 0) list.removeAt(index)
        list += instance
        list
    }

    suspend fun remove(id: String) = edit { list -> list.filterNot { it.id == id } }

    suspend fun update(id: String, transform: (DistroInstance) -> DistroInstance) = edit { list ->
        list.map { if (it.id == id) transform(it) else it }
    }

    private suspend fun edit(block: (MutableList<DistroInstance>) -> List<DistroInstance>) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<MutableList<DistroInstance>>(it) }.getOrDefault(mutableListOf())
            } ?: mutableListOf()
            prefs[key] = json.encodeToString(block(current))
        }
    }

    companion object {
        fun newId(codeName: String, username: String): String =
            "$codeName-${username.lowercase().filter { it.isLetterOrDigit() }}-${System.currentTimeMillis() % 100000}"
    }
}

class SettingsRepository(private val context: Context) {
    private val kTheme = stringPreferencesKey("theme_mode")
    private val kDynamic = androidx.datastore.preferences.core.booleanPreferencesKey("dynamic")
    private val kFont = androidx.datastore.preferences.core.floatPreferencesKey("font")
    private val kW = androidx.datastore.preferences.core.intPreferencesKey("geo_w")
    private val kH = androidx.datastore.preferences.core.intPreferencesKey("geo_h")
    private val kAudio = androidx.datastore.preferences.core.booleanPreferencesKey("audio")
    private val kKernel = stringPreferencesKey("kernel")
    private val kMirror = stringPreferencesKey("mirror")
    private val kSource = stringPreferencesKey("rootfs_source")
    private val kBindPrefix = "binds_"
    private val kBinds = androidx.datastore.preferences.core.stringSetPreferencesKey("binds")

    val settings: Flow<dev.ubuntu4a.core.data.model.AppSettings> = context.dataStore.data.map { p ->
        dev.ubuntu4a.core.data.model.AppSettings(
            themeMode = runCatching { dev.ubuntu4a.core.data.model.ThemeMode.valueOf(p[kTheme] ?: "") }
                .getOrDefault(dev.ubuntu4a.core.data.model.ThemeMode.SYSTEM),
            dynamicColor = p[kDynamic] ?: true,
            terminalFontSizeSp = p[kFont] ?: 12f,
            desktopWidth = p[kW] ?: 1280,
            desktopHeight = p[kH] ?: 720,
            audioEnabled = p[kAudio] ?: true,
            kernelReleaseSpoof = p[kKernel] ?: "6.18.3-fake",
            rootfsMirror = p[kMirror] ?: "https://partner-images.canonical.com/oci",
            rootfsSource = runCatching { RootfsSource.valueOf(p[kSource] ?: "") }.getOrDefault(RootfsSource.RELEASE),
        )
    }

    suspend fun set(transform: (dev.ubuntu4a.core.data.model.AppSettings) -> dev.ubuntu4a.core.data.model.AppSettings) {
        val current = first()
        val next = transform(current)
        context.dataStore.edit { p ->
            p[kTheme] = next.themeMode.name
            p[kDynamic] = next.dynamicColor
            p[kFont] = next.terminalFontSizeSp
            p[kW] = next.desktopWidth
            p[kH] = next.desktopHeight
            p[kAudio] = next.audioEnabled
            p[kKernel] = next.kernelReleaseSpoof
            p[kMirror] = next.rootfsMirror
            p[kSource] = next.rootfsSource.name
        }
    }

    private val updateScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun update(transform: (dev.ubuntu4a.core.data.model.AppSettings) -> dev.ubuntu4a.core.data.model.AppSettings) {
        updateScope.launch { set(transform) }
    }

    suspend fun first(): dev.ubuntu4a.core.data.model.AppSettings = settings.first()

    fun bindsFor(instanceId: String): Flow<List<BindEntry>> = context.dataStore.data.map { p ->
        p[kBinds]?.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }?.filter { it.first == instanceId }?.map { BindEntry.parse(it.second) }?.filterNotNull() ?: emptyList()
    }

    suspend fun setBinds(instanceId: String, binds: List<BindEntry>) {
        context.dataStore.edit { p ->
            val others = (p[kBinds] ?: emptySet()).filterNot { it.startsWith("$instanceId=") }
            p[kBinds] = (others + binds.map { "$instanceId=${it.toLine()}" }).toSet()
        }
    }
}
