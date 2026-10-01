package lt.kantrybes.tvremote.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import lt.kantrybes.tvremote.data.remote.WakeOnLan

internal data class SettingsSnapshot(
    val tvIp: String,
    val pairedHost: String,
    val tvMac: String,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

internal class SettingsRepository(private val context: Context) {
    suspend fun read(): SettingsSnapshot {
        val prefs = context.settingsDataStore.data.first()
        return SettingsSnapshot(
            tvIp = prefs[Keys.TV_IP].orEmpty(),
            pairedHost = prefs[Keys.PAIRED_HOST].orEmpty(),
            tvMac = prefs[Keys.TV_MAC]?.takeIf { it.isNotBlank() } ?: WakeOnLan.DEFAULT_MAC,
        )
    }

    suspend fun setTvMac(mac: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TV_MAC] = mac.trim()
        }
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

    fun controlMode(): Flow<ControlMode> {
        return context.settingsDataStore.data.map { prefs ->
            ControlMode.fromStored(prefs[Keys.CONTROL_MODE])
        }
    }

    suspend fun setControlMode(mode: ControlMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CONTROL_MODE] = mode.stored()
        }
    }

    suspend fun migrateControlMode() {
        context.settingsDataStore.edit { prefs ->
            val raw = prefs[Keys.CONTROL_MODE] ?: return@edit
            if (raw.trim().equals(ControlMode.SWIPE_VALUE, ignoreCase = true)) {
                prefs[Keys.CONTROL_MODE] = ControlMode.DPAD.stored()
            }
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

    fun showMediaControls(): Flow<Boolean> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[Keys.SHOW_MEDIA_CONTROLS] ?: true
        }
    }

    suspend fun setShowMediaControls(show: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SHOW_MEDIA_CONTROLS] = show
        }
    }

    fun pictureSliders(): Flow<List<PictureSliderPref>> {
        return context.settingsDataStore.data.map { prefs ->
            PictureSlider.items.map { item ->
                PictureSliderPref(
                    item = item,
                    shown = prefs[Keys.sliderShown(item)] ?: PictureSlider.shownByDefault(item),
                    value = prefs[Keys.sliderValue(item)] ?: DEFAULT_PICTURE_VALUE,
                )
            }
        }
    }

    suspend fun setPictureSliderShown(item: String, shown: Boolean) {
        if (!PictureSlider.isItem(item)) return
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.sliderShown(item)] = shown
        }
    }

    suspend fun setPictureValue(item: String, value: Int) {
        if (!PictureSlider.isItem(item)) return
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.sliderValue(item)] = value.coerceIn(0, 100)
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

    suspend fun launcherComponent(packageName: String): String? {
        if (!Shortcuts.isPackageName(packageName)) return null
        val stored = context.settingsDataStore.data.first()[Keys.launcherComponent(packageName)] ?: return null
        if (stored.any { it.isWhitespace() } || !stored.startsWith("$packageName/")) return null
        return stored
    }

    suspend fun setLauncherComponent(packageName: String, component: String) {
        if (!Shortcuts.isPackageName(packageName)) return
        if (component.any { it.isWhitespace() } || !component.startsWith("$packageName/")) return
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.launcherComponent(packageName)] = component
        }
    }

    suspend fun clearLauncherComponent(packageName: String) {
        if (!Shortcuts.isPackageName(packageName)) return
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.launcherComponent(packageName))
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
        val TV_MAC = stringPreferencesKey("tv_mac")
        val PAIRED_HOST = stringPreferencesKey("paired_host")
        val SHORTCUTS = stringPreferencesKey("shortcuts")
        val SHOW_PICTURE_MENU = booleanPreferencesKey("show_picture_menu_button")
        val SHOW_MEDIA_CONTROLS = booleanPreferencesKey("show_media_controls")
        val CONTROL_MODE = stringPreferencesKey("control_mode")

        fun sliderShown(item: String) = booleanPreferencesKey("picture_show_$item")

        fun sliderValue(item: String) = intPreferencesKey("picture_value_$item")

        fun launcherComponent(packageName: String) = stringPreferencesKey("launcher_component_$packageName")
    }

    private companion object {
        const val DEFAULT_PICTURE_VALUE = 50
    }
}
