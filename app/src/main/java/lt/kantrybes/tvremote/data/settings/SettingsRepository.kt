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
import lt.kantrybes.tvremote.data.remote.InvalidTvAddressException
import lt.kantrybes.tvremote.data.remote.TvAddress
import lt.kantrybes.tvremote.data.remote.WakeOnLan
import lt.kantrybes.tvremote.data.scrcpy.MirrorPreset

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
        val trimmed = ip.trim()
        if (isPublicAddress(trimmed)) return
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TV_IP] = trimmed
        }
    }

    suspend fun setPairedHost(host: String) {
        val trimmed = host.trim()
        val previous = read().pairedHost
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PAIRED_HOST] = trimmed
        }
        if (trimmed.isEmpty() && previous.isNotBlank()) {
            clearServerPin(previous)
        }
    }

    suspend fun serverPin(host: String): ByteArray? {
        val key = Keys.serverPin(host) ?: return null
        val stored = context.settingsDataStore.data.first()[key]?.takeIf { it.isNotBlank() } ?: return null
        return decodePin(stored) ?: ByteArray(0)
    }

    suspend fun setServerPin(host: String, fingerprint: ByteArray) {
        val key = Keys.serverPin(host) ?: return
        val encoded = fingerprint.toPinHex()
        context.settingsDataStore.edit { prefs ->
            prefs[key] = encoded
        }
    }

    suspend fun clearServerPin(host: String) {
        val key = Keys.serverPin(host) ?: return
        context.settingsDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    suspend fun launcherToken(host: String): String? {
        val key = Keys.launcherToken(host) ?: return null
        return context.settingsDataStore.data.first()[key]?.takeIf { it.isNotBlank() }
    }

    suspend fun setLauncherToken(host: String, token: String) {
        val key = Keys.launcherToken(host) ?: return
        val value = token.trim()
        if (value.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            prefs[key] = value
        }
    }

    suspend fun clearLauncherToken(host: String) {
        val key = Keys.launcherToken(host) ?: return
        context.settingsDataStore.edit { prefs ->
            prefs.remove(key)
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

    fun mirrorFullscreen(): Flow<Boolean> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[Keys.MIRROR_FULLSCREEN] ?: false
        }
    }

    suspend fun setMirrorFullscreen(fullscreen: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.MIRROR_FULLSCREEN] = fullscreen
        }
    }

    fun mirrorPreset(): Flow<MirrorPreset> {
        return context.settingsDataStore.data.map { prefs ->
            MirrorPreset.fromStored(prefs[Keys.MIRROR_PRESET])
        }
    }

    suspend fun setMirrorPreset(preset: MirrorPreset) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.MIRROR_PRESET] = preset.wire
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
        val MIRROR_FULLSCREEN = booleanPreferencesKey("mirror_fullscreen")
        val MIRROR_PRESET = stringPreferencesKey("mirror_preset")
        val CONTROL_MODE = stringPreferencesKey("control_mode")

        fun sliderShown(item: String) = booleanPreferencesKey("picture_show_$item")

        fun sliderValue(item: String) = intPreferencesKey("picture_value_$item")

        fun launcherComponent(packageName: String) = stringPreferencesKey("launcher_component_$packageName")

        fun launcherToken(host: String) = try {
            stringPreferencesKey("launcher_token_${TvAddress.requireIpv4(host)}")
        } catch (_: InvalidTvAddressException) {
            null
        }

        fun serverPin(host: String) = try {
            stringPreferencesKey("server_pin_${TvAddress.requireIpv4(host)}")
        } catch (_: InvalidTvAddressException) {
            null
        }
    }

    private fun isPublicAddress(value: String): Boolean {
        if (value.isEmpty()) return false
        return try {
            TvAddress.requireIpv4(value)
            false
        } catch (error: InvalidTvAddressException) {
            error.kind == InvalidTvAddressException.Kind.NOT_PRIVATE
        }
    }

    private companion object {
        const val DEFAULT_PICTURE_VALUE = 50
    }
}

private fun ByteArray.toPinHex(): String {
    return joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun decodePin(hex: String): ByteArray? {
    if (hex.length != 64) return null
    val out = ByteArray(32)
    for (index in out.indices) {
        val start = index * 2
        val byte = hex.substring(start, start + 2).toIntOrNull(16) ?: return null
        out[index] = byte.toByte()
    }
    return out
}
