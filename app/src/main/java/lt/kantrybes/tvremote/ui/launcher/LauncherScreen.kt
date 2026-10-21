package lt.kantrybes.tvremote.ui.launcher

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.data.launcher.LAUNCHER_WALLPAPERS
import lt.kantrybes.tvremote.data.launcher.LauncherApp
import lt.kantrybes.tvremote.ui.LocalNetworkAccess
import lt.kantrybes.tvremote.ui.LocalNetworkDeniedMessage

private val CardShape = RoundedCornerShape(12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherScreen(
    network: LocalNetworkAccess,
    onClose: () -> Unit,
    viewModel: LauncherViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val generation by viewModel.iconGeneration.collectAsStateWithLifecycle()
    val pending = (state as? LauncherUiState.Manager)?.pending == true
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    LaunchedEffect(pending) {
        if (pending) network.request { viewModel.ensureLoaded() }
    }
    val manager = state as? LauncherUiState.Manager
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClose()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.launcher_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.launcher)) },
                actions = {
                    if (manager != null) {
                        if (manager.refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    network.request { viewModel.refresh() }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = stringResource(R.string.launcher_refresh),
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more),
                                    contentDescription = stringResource(R.string.launcher_more),
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.launcher_repair)) },
                                    onClick = {
                                        menuOpen = false
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.repair()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            LauncherUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is LauncherUiState.Pairing -> PairingForm(
                state = current,
                network = network,
                contentPadding = innerPadding,
                onIpChange = viewModel::onIpChange,
                onPinChange = viewModel::onPinChange,
                onConnect = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    network.request { viewModel.connect() }
                },
            )
            is LauncherUiState.Manager -> ManagerList(
                state = current,
                network = network,
                generation = generation,
                contentPadding = innerPadding,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun PairingForm(
    state: LauncherUiState.Pairing,
    network: LocalNetworkAccess,
    contentPadding: PaddingValues,
    onIpChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (network.denied && !network.granted) {
            LocalNetworkDeniedMessage(network)
        }
        OutlinedTextField(
            value = state.ip,
            onValueChange = onIpChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.launcher_ip)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = if (state.showPin) ImeAction.Next else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onConnect() }),
        )
        if (state.showPin) {
            OutlinedTextField(
                value = state.pin,
                onValueChange = onPinChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.launcher_pin)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onConnect() }),
            )
        }
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy && state.ip.isNotBlank(),
        ) {
            if (state.busy) {
                val description = stringResource(R.string.working)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = description },
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.launcher_connect))
            }
        }
        if (state.error != null) {
            Text(text = state.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ManagerList(
    state: LauncherUiState.Manager,
    network: LocalNetworkAccess,
    generation: Int,
    contentPadding: PaddingValues,
    viewModel: LauncherViewModel,
) {
    val haptics = LocalHapticFeedback.current
    val query = state.query.trim()
    val hiddenIds = state.hidden.toSet()
    val visibleApps = state.apps.filter { app ->
        app.packageName !in hiddenIds && matches(app, query)
    }
    val hiddenApps = hiddenApps(state, query)
    val waiting = state.pending || (state.refreshing && state.apps.isEmpty() && state.error == null)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "status") {
            StatusLine(state = state, network = network)
        }
        if (waiting) {
            item(key = "loading") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            return@LazyColumn
        }
        item(key = "favorites-title") { SectionHeader(stringResource(R.string.launcher_favorites)) }
        if (state.favorites.isEmpty()) {
            item(key = "favorites-empty") {
                Text(
                    text = stringResource(R.string.launcher_favorites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(state.favorites, key = { _, packageName -> "fav-$packageName" }) { index, packageName ->
            FavoriteCard(
                label = state.apps.firstOrNull { it.packageName == packageName }?.label ?: packageName,
                packageName = packageName,
                generation = generation,
                icon = remember(generation, packageName) { viewModel.cachedIcon(packageName) },
                canMoveUp = index > 0,
                canMoveDown = index < state.favorites.lastIndex,
                onMoveUp = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.moveFavorite(packageName, -1)
                },
                onMoveDown = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.moveFavorite(packageName, 1)
                },
                onRemove = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.removeFavorite(packageName)
                },
                onVisible = { viewModel.ensureIcon(packageName) },
            )
        }
        item(key = "wallpaper-title") { SectionHeader(stringResource(R.string.launcher_wallpaper)) }
        item(key = "wallpaper") {
            WallpaperRow(
                selected = state.wallpaper,
                onSelect = { id ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.selectWallpaper(id)
                },
            )
        }
        item(key = "apps-title") { SectionHeader(stringResource(R.string.launcher_apps)) }
        item(key = "search") {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                label = { Text(stringResource(R.string.launcher_search)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                    )
                },
                singleLine = true,
            )
        }
        if (visibleApps.isEmpty() && (query.isNotEmpty() || state.error == null)) {
            item(key = "apps-empty") {
                Text(
                    text = stringResource(R.string.launcher_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(visibleApps, key = { "app-${it.packageName}" }) { app ->
            AppCard(
                app = app,
                generation = generation,
                icon = remember(generation, app.packageName) { viewModel.cachedIcon(app.packageName) },
                favorite = app.packageName in state.favorites,
                hidden = false,
                dimmed = false,
                onFavorite = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setFavorite(app.packageName, it)
                },
                onHidden = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setHidden(app.packageName, it)
                },
                onOpen = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.openOnTv(app.packageName)
                },
                onVisible = { viewModel.ensureIcon(app.packageName) },
            )
        }
        if (hiddenApps.isNotEmpty()) {
            item(key = "hidden-title") { SectionHeader(stringResource(R.string.launcher_hidden)) }
            items(hiddenApps, key = { "hidden-${it.packageName}" }) { app ->
                AppCard(
                    app = app,
                    generation = generation,
                    icon = remember(generation, app.packageName) { viewModel.cachedIcon(app.packageName) },
                    favorite = app.packageName in state.favorites,
                    hidden = true,
                    dimmed = true,
                    onFavorite = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.setFavorite(app.packageName, it)
                    },
                    onHidden = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.setHidden(app.packageName, it)
                    },
                    onOpen = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.openOnTv(app.packageName)
                    },
                    onVisible = { viewModel.ensureIcon(app.packageName) },
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: LauncherUiState.Manager, network: LocalNetworkAccess) {
    val text = when {
        network.denied && !network.granted -> stringResource(R.string.local_network_denied)
        state.error != null -> state.error
        state.pending || (state.refreshing && state.apps.isEmpty()) ->
            stringResource(R.string.status_connecting)
        state.host.isNotBlank() -> stringResource(R.string.launcher_connected, state.host)
        else -> return
    }
    val problem = state.error != null || (network.denied && !network.granted)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FavoriteCard(
    label: String,
    packageName: String,
    generation: Int,
    icon: Bitmap?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onVisible: () -> Unit,
) {
    LaunchedEffect(packageName, generation) { onVisible() }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppIcon(icon)
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CompactIconButton(
                icon = R.drawable.ic_arrow_up,
                description = stringResource(R.string.launcher_up),
                enabled = canMoveUp,
                onClick = onMoveUp,
            )
            CompactIconButton(
                icon = R.drawable.ic_arrow_down,
                description = stringResource(R.string.launcher_down),
                enabled = canMoveDown,
                onClick = onMoveDown,
            )
            CompactIconButton(
                icon = R.drawable.ic_close,
                description = stringResource(R.string.launcher_remove),
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun AppCard(
    app: LauncherApp,
    generation: Int,
    icon: Bitmap?,
    favorite: Boolean,
    hidden: Boolean,
    dimmed: Boolean,
    onFavorite: (Boolean) -> Unit,
    onHidden: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onVisible: () -> Unit,
) {
    LaunchedEffect(app.packageName, generation) { onVisible() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppIcon(icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CompactIconButton(
                icon = if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star,
                description = stringResource(R.string.launcher_favorite),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onFavorite(!favorite) },
            )
            CompactIconButton(
                icon = R.drawable.ic_visibility_off,
                description = stringResource(R.string.launcher_hidden),
                tint = if (hidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onHidden(!hidden) },
            )
            CompactIconButton(
                icon = R.drawable.ic_play,
                description = stringResource(R.string.launcher_open),
                onClick = onOpen,
            )
        }
    }
}

@Composable
private fun CompactIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(22.dp),
            tint = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun AppIcon(icon: Bitmap?) {
    if (icon == null) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun WallpaperRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        wallpaperOptions.forEach { option ->
            val label = stringResource(option.label)
            FilterChip(
                selected = option.id == selected,
                onClick = { onSelect(option.id) },
                label = { Text(label) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(option.color)),
                    )
                },
            )
        }
    }
}

private fun matches(app: LauncherApp, query: String): Boolean {
    if (query.isEmpty()) return true
    return app.label.contains(query, ignoreCase = true) ||
        app.packageName.contains(query, ignoreCase = true)
}

private fun hiddenApps(state: LauncherUiState.Manager, query: String): List<LauncherApp> {
    return state.hidden.mapNotNull { packageName ->
        val known = state.apps.firstOrNull { it.packageName == packageName }
        val app = known ?: LauncherApp(packageName, packageName)
        if (matches(app, query)) app else null
    }
}

private data class WallpaperOption(val id: String, val label: Int, val color: Long)

private val wallpaperOptions = listOf(
    WallpaperOption("night", R.string.launcher_wallpaper_night, 0xFF12121C),
    WallpaperOption("aurora", R.string.launcher_wallpaper_aurora, 0xFF1B4D3E),
    WallpaperOption("sunset", R.string.launcher_wallpaper_sunset, 0xFFC4552A),
    WallpaperOption("forest", R.string.launcher_wallpaper_forest, 0xFF1E4D2B),
    WallpaperOption("ocean", R.string.launcher_wallpaper_ocean, 0xFF1A4E7A),
    WallpaperOption("graphite", R.string.launcher_wallpaper_graphite, 0xFF3A3F45),
).also { options ->
    check(options.map { it.id } == LAUNCHER_WALLPAPERS)
}
