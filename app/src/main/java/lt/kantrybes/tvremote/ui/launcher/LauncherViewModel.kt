package lt.kantrybes.tvremote.ui.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import lt.kantrybes.tvremote.BuildConfig
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.data.launcher.LAUNCHER_WALLPAPERS
import lt.kantrybes.tvremote.data.launcher.LauncherApp
import lt.kantrybes.tvremote.data.launcher.LauncherCallException
import lt.kantrybes.tvremote.data.launcher.LauncherClient
import lt.kantrybes.tvremote.data.launcher.isLauncherPackage
import lt.kantrybes.tvremote.data.remote.InvalidTvAddressException
import lt.kantrybes.tvremote.data.remote.TvAddress
import lt.kantrybes.tvremote.data.settings.SettingsRepository

internal sealed interface LauncherUiState {
    data object Loading : LauncherUiState

    data class Pairing(
        val ip: String,
        val pin: String,
        val showPin: Boolean,
        val busy: Boolean,
        val error: String?,
    ) : LauncherUiState

    data class Manager(
        val apps: List<LauncherApp>,
        val favorites: List<String>,
        val hidden: List<String>,
        val wallpaper: String,
        val query: String,
        val pending: Boolean,
        val refreshing: Boolean,
        val error: String?,
        val host: String = "",
    ) : LauncherUiState
}

internal class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)
    private val api = LauncherClient()
    private val icons = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val iconSlots = Semaphore(4)

    private val _state = MutableStateFlow<LauncherUiState>(LauncherUiState.Loading)
    val state: StateFlow<LauncherUiState> = _state.asStateFlow()

    private val _iconGeneration = MutableStateFlow(0)
    val iconGeneration: StateFlow<Int> = _iconGeneration.asStateFlow()

    private var host = ""
    private var token = ""
    private var favoritesJob: Job? = null
    private var hiddenJob: Job? = null
    private var loadJob: Job? = null
    private var editGeneration = 0
    private val loadStarted = AtomicBoolean(false)
    private val signingOut = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            val snapshot = settings.read()
            val ip = snapshot.tvIp.trim().ifBlank { snapshot.pairedHost.trim() }
            val saved = runCatching { TvAddress.requireIpv4(ip) }.getOrNull()
                ?.let { settings.launcherToken(it) }
            if (saved.isNullOrBlank()) {
                host = ip
                _state.value = LauncherUiState.Pairing(
                    ip = ip,
                    pin = "",
                    showPin = true,
                    busy = false,
                    error = null,
                )
            } else {
                host = TvAddress.requireIpv4(ip)
                token = saved
                _state.value = emptyManager(pending = true)
            }
        }
    }

    fun onIpChange(value: String) {
        val pairing = _state.value as? LauncherUiState.Pairing ?: return
        _state.value = pairing.copy(ip = value, error = null)
    }

    fun onPinChange(value: String) {
        val pairing = _state.value as? LauncherUiState.Pairing ?: return
        _state.value = pairing.copy(pin = value, error = null)
    }

    fun onQueryChange(value: String) {
        val manager = _state.value as? LauncherUiState.Manager ?: return
        _state.value = manager.copy(query = value)
    }

    fun connect() {
        val pairing = _state.value as? LauncherUiState.Pairing ?: return
        if (pairing.busy) return
        viewModelScope.launch {
            if (BuildConfig.IS_DEMO) {
                _state.value = pairing.copy(busy = false, error = demoMessage())
                return@launch
            }
            val ip = try {
                TvAddress.requireIpv4(pairing.ip)
            } catch (error: InvalidTvAddressException) {
                _state.value = pairing.copy(error = ipError(error))
                return@launch
            }
            _state.value = pairing.copy(ip = ip, busy = true, error = null)
            try {
                val saved = settings.launcherToken(ip)
                val next = if (!saved.isNullOrBlank()) {
                    saved
                } else {
                    val pin = pairing.pin.trim()
                    if (pin.isEmpty()) {
                        _state.value = pairing.copy(
                            ip = ip,
                            showPin = true,
                            busy = false,
                            error = text(R.string.launcher_error_pin_empty),
                        )
                        return@launch
                    }
                    val issued = withContext(Dispatchers.IO) { api.pair(ip, pin) }
                    settings.setLauncherToken(ip, issued)
                    issued
                }
                settings.setTvIp(ip)
                host = ip
                token = next
                signingOut.set(false)
                _state.value = emptyManager(pending = false).copy(refreshing = true)
                loadState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: LauncherCallException) {
                val kind = if (error.kind == LauncherCallException.Kind.UNAUTHORIZED) {
                    LauncherCallException.Kind.WRONG_PIN
                } else {
                    error.kind
                }
                _state.value = pairing.copy(ip = ip, showPin = true, busy = false, error = text(kind))
            }
        }
    }

    fun ensureLoaded() {
        if (BuildConfig.IS_DEMO) {
            val manager = _state.value as? LauncherUiState.Manager ?: return
            _state.value = manager.copy(pending = false, refreshing = false, error = demoMessage())
            return
        }
        if (token.isBlank()) return
        if (!loadStarted.compareAndSet(false, true)) return
        loadJob = viewModelScope.launch { loadState() }
    }

    fun refresh() {
        if (BuildConfig.IS_DEMO) {
            val manager = _state.value as? LauncherUiState.Manager ?: return
            _state.value = manager.copy(error = demoMessage(), pending = false, refreshing = false)
            return
        }
        if (token.isBlank()) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadState() }
    }

    fun repair() {
        viewModelScope.launch {
            favoritesJob?.cancel()
            hiddenJob?.cancel()
            loadJob?.cancel()
            val ip = host
            settings.clearLauncherToken(ip)
            token = ""
            loadStarted.set(false)
            _state.value = LauncherUiState.Pairing(
                ip = ip,
                pin = "",
                showPin = true,
                busy = false,
                error = null,
            )
        }
    }

    fun moveFavorite(packageName: String, delta: Int) {
        val manager = _state.value as? LauncherUiState.Manager ?: return
        val list = manager.favorites.toMutableList()
        val index = list.indexOf(packageName)
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        val item = list.removeAt(index)
        list.add(target, item)
        _state.value = manager.copy(favorites = list, error = null)
        scheduleFavorites()
    }

    fun removeFavorite(packageName: String) {
        updateFavorites { it.remove(packageName) }
    }

    fun setFavorite(packageName: String, favorite: Boolean) {
        if (!isLauncherPackage(packageName)) return
        updateFavorites { list ->
            if (favorite) {
                if (packageName !in list) list.add(packageName)
            } else {
                list.remove(packageName)
            }
        }
    }

    fun setHidden(packageName: String, hidden: Boolean) {
        if (!isLauncherPackage(packageName)) return
        val manager = _state.value as? LauncherUiState.Manager ?: return
        val list = manager.hidden.toMutableList()
        if (hidden) {
            if (packageName !in list) list.add(packageName)
        } else {
            list.remove(packageName)
        }
        _state.value = manager.copy(hidden = list, error = null)
        scheduleHidden()
    }

    fun selectWallpaper(id: String) {
        if (id !in LAUNCHER_WALLPAPERS) return
        val manager = _state.value as? LauncherUiState.Manager ?: return
        if (manager.pending || manager.wallpaper == id) return
        val previous = manager.wallpaper
        _state.value = manager.copy(wallpaper = id, error = null)
        val hostNow = host
        val tokenNow = token
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.putWallpaper(hostNow, tokenNow, id) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LauncherCallException) {
                if (token != tokenNow) return@launch
                val current = _state.value as? LauncherUiState.Manager
                if (current != null && error.kind != LauncherCallException.Kind.UNAUTHORIZED) {
                    _state.value = current.copy(wallpaper = previous)
                }
                report(error)
            }
        }
    }

    fun openOnTv(packageName: String) {
        if (!isLauncherPackage(packageName)) return
        val hostNow = host
        val tokenNow = token
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.launch(hostNow, tokenNow, packageName) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LauncherCallException) {
                if (token == tokenNow) report(error)
            }
        }
    }

    fun cachedIcon(packageName: String): Bitmap? = icons.get(iconKey(packageName))

    fun ensureIcon(packageName: String) {
        if (token.isBlank() || BuildConfig.IS_DEMO || !isLauncherPackage(packageName)) return
        val key = iconKey(packageName)
        if (icons.get(key) != null || !inFlight.add(key)) return
        val hostNow = host
        val tokenNow = token
        viewModelScope.launch(Dispatchers.IO) {
            try {
                iconSlots.acquire()
                try {
                    val bytes = api.icon(hostNow, tokenNow, packageName)
                    val bitmap = decodeIcon(bytes) ?: return@launch
                    icons.put(key, bitmap)
                    _iconGeneration.value = _iconGeneration.value + 1
                } finally {
                    iconSlots.release()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LauncherCallException) {
                if (error.kind == LauncherCallException.Kind.UNAUTHORIZED && token == tokenNow) {
                    signOut()
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun updateFavorites(edit: (MutableList<String>) -> Unit) {
        val manager = _state.value as? LauncherUiState.Manager ?: return
        val list = manager.favorites.toMutableList()
        edit(list)
        _state.value = manager.copy(favorites = list, error = null)
        scheduleFavorites()
    }

    private fun scheduleFavorites() {
        editGeneration += 1
        val hostNow = host
        val tokenNow = token
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            delay(FAVORITES_DEBOUNCE_MS)
            val packages = (_state.value as? LauncherUiState.Manager)?.favorites ?: return@launch
            try {
                withContext(Dispatchers.IO) { api.putFavorites(hostNow, tokenNow, packages) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LauncherCallException) {
                if (token == tokenNow) report(error)
            }
        }
    }

    private fun scheduleHidden() {
        editGeneration += 1
        val hostNow = host
        val tokenNow = token
        hiddenJob?.cancel()
        hiddenJob = viewModelScope.launch {
            delay(FAVORITES_DEBOUNCE_MS)
            val packages = (_state.value as? LauncherUiState.Manager)?.hidden ?: return@launch
            try {
                withContext(Dispatchers.IO) { api.putHidden(hostNow, tokenNow, packages) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LauncherCallException) {
                if (token == tokenNow) report(error)
            }
        }
    }

    private suspend fun loadState() {
        if (BuildConfig.IS_DEMO) {
            report(LauncherCallException(LauncherCallException.Kind.UNREACHABLE))
            return
        }
        val hostNow = host
        val tokenNow = token
        if (tokenNow.isBlank()) return
        awaitPendingEdits()
        val seen = editGeneration
        val existing = _state.value as? LauncherUiState.Manager
        if (existing != null) {
            _state.value = existing.copy(refreshing = true, pending = false, error = null)
        }
        try {
            val snapshot = withContext(Dispatchers.IO) { api.state(hostNow, tokenNow) }
            if (token != tokenNow) return
            if (editGeneration != seen) {
                val current = _state.value as? LauncherUiState.Manager
                if (current != null) {
                    _state.value = current.copy(refreshing = false, pending = false)
                }
                return
            }
            val query = (_state.value as? LauncherUiState.Manager)?.query.orEmpty()
            _state.value = LauncherUiState.Manager(
                apps = snapshot.apps,
                favorites = snapshot.favorites,
                hidden = snapshot.hidden,
                wallpaper = snapshot.wallpaper,
                query = query,
                pending = false,
                refreshing = false,
                error = null,
                host = hostNow,
            )
            _iconGeneration.value = _iconGeneration.value + 1
        } catch (error: CancellationException) {
            throw error
        } catch (error: LauncherCallException) {
            if (token == tokenNow) report(error)
        }
    }

    private suspend fun awaitPendingEdits() {
        while (currentCoroutineContext().isActive) {
            val pending = listOfNotNull(favoritesJob, hiddenJob).filter { it.isActive }
            if (pending.isEmpty()) return
            pending.forEach { job ->
                try {
                    job.join()
                } catch (error: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw error
                }
            }
        }
    }

    private suspend fun report(error: LauncherCallException) {
        if (error.kind == LauncherCallException.Kind.UNAUTHORIZED) {
            signOut()
            return
        }
        val message = if (BuildConfig.IS_DEMO) demoMessage() else text(error.kind)
        when (val current = _state.value) {
            is LauncherUiState.Manager -> {
                _state.value = current.copy(error = message, refreshing = false, pending = false)
            }
            is LauncherUiState.Pairing -> _state.value = current.copy(error = message, busy = false)
            LauncherUiState.Loading -> {
                _state.value = LauncherUiState.Pairing(
                    ip = host,
                    pin = "",
                    showPin = true,
                    busy = false,
                    error = message,
                )
            }
        }
    }

    private suspend fun signOut() {
        if (!signingOut.compareAndSet(false, true)) return
        favoritesJob?.cancel()
        hiddenJob?.cancel()
        loadJob?.cancel()
        val ip = host
        settings.clearLauncherToken(ip)
        token = ""
        loadStarted.set(false)
        _state.value = LauncherUiState.Pairing(
            ip = ip,
            pin = "",
            showPin = true,
            busy = false,
            error = text(R.string.launcher_error_token),
        )
    }

    private fun emptyManager(pending: Boolean) = LauncherUiState.Manager(
        apps = emptyList(),
        favorites = emptyList(),
        hidden = emptyList(),
        wallpaper = "",
        query = "",
        pending = pending,
        refreshing = false,
        error = null,
        host = host,
    )

    private fun iconKey(packageName: String) = "$host/$packageName"

    private fun decodeIcon(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 180) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scale = minOf(1f, MAX_ICON_WIDTH / decoded.width, MAX_ICON_HEIGHT / decoded.height)
        if (scale >= 1f) return decoded
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    private fun text(kind: LauncherCallException.Kind): String {
        val res = when (kind) {
            LauncherCallException.Kind.UNREACHABLE -> R.string.launcher_error_unreachable
            LauncherCallException.Kind.NOT_HOME -> R.string.launcher_error_home
            LauncherCallException.Kind.WRONG_PIN -> R.string.launcher_error_pin
            LauncherCallException.Kind.UNAUTHORIZED -> R.string.launcher_error_token
            LauncherCallException.Kind.BAD_WALLPAPER -> R.string.launcher_error_wallpaper
            LauncherCallException.Kind.FAILED -> R.string.launcher_error_generic
        }
        return text(res)
    }

    private fun text(res: Int) = getApplication<Application>().getString(res)

    private fun demoMessage() = text(R.string.demo_intro_body)

    private fun ipError(error: InvalidTvAddressException): String {
        val res = when (error.kind) {
            InvalidTvAddressException.Kind.EMPTY -> R.string.error_ip_empty
            InvalidTvAddressException.Kind.INVALID -> R.string.error_ip_invalid
        }
        return text(res)
    }

    private companion object {
        const val FAVORITES_DEBOUNCE_MS = 500L
        const val MAX_ICON_WIDTH = 160f
        const val MAX_ICON_HEIGHT = 90f
    }
}
