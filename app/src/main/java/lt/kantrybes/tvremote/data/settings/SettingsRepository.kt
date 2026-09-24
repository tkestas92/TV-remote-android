package lt.kantrybes.tvremote.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class SettingsSnapshot(
    val tvIp: String,
    val pairedHost: String,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

internal class SettingsRepository(private val context: Context) {
    suspend fun read(): SettingsSnapshot {
        val prefs = context.settingsDataStore.data.first()
        return SettingsSnapshot(
            tvIp = prefs[Keys.TV_IP].orEmpty(),
            pairedHost = prefs[Keys.PAIRED_HOST].orEmpty(),
        )
    }

    suspend fun setTvIp(ip: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TV_IP] = ip.trim()
        }
    }

    suspend fun setPairedHost(host: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PAIRED_HOST] = host.trim()
        }
    }

    fun showPictureMenuButton(): Flow<Boolean> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[Keys.SHOW_PICTURE_MENU] ?: true
        }
    }

    suspend fun setShowPictureMenuButton(show: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SHOW_PICTURE_MENU] = show
        }
    }

    suspend fun supportsAppLinks(host: String): Boolean {
        if (host.isBlank()) return false
        val prefs = context.settingsDataStore.data.first()
        return prefs[booleanPreferencesKey("supports_app_links_$host")] ?: false
    }

    fun shortcuts(): Flow<List<AppShortcut>> {
        return context.settingsDataStore.data.map { prefs ->
            Shortcuts.decode(prefs[Keys.SHORTCUTS])
        }
    }

    suspend fun setShortcut(index: Int, shortcut: AppShortcut) {
        if (!Shortcuts.isValid(shortcut)) return
        context.settingsDataStore.edit { prefs ->
            val current = Shortcuts.decode(prefs[Keys.SHORTCUTS]).toMutableList()
            if (index !in current.indices) return@edit
            current[index] = shortcut
            prefs[Keys.SHORTCUTS] = Shortcuts.encode(current)
        }
    }

    private object Keys {
        val TV_IP = stringPreferencesKey("tv_ip")
        val PAIRED_HOST = stringPreferencesKey("paired_host")
        val SHORTCUTS = stringPreferencesKey("shortcuts")
        val SHOW_PICTURE_MENU = booleanPreferencesKey("show_picture_menu_button")
    }
}
