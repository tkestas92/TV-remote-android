package lt.kantrybes.tvremote.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import lt.kantrybes.tvremote.BuildConfig
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.ui.demo.DemoChip
import lt.kantrybes.tvremote.ui.demo.VirtualTv
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.data.scrcpy.MirrorPreset
import lt.kantrybes.tvremote.data.scrcpy.MirrorStage
import lt.kantrybes.tvremote.data.scrcpy.MirrorState
import lt.kantrybes.tvremote.data.scrcpy.ScrcpyProtocol
import lt.kantrybes.tvremote.ui.remote.DirectionPad
import lt.kantrybes.tvremote.ui.remote.KeyButton

@Composable
internal fun ScreenScreen(
    onClose: () -> Unit,
    viewModel: ScreenViewModel = viewModel(),
) {
    val mirrorState by viewModel.mirrorState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val fullscreen by viewModel.fullscreen.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val preset by viewModel.preset.collectAsStateWithLifecycle()
    val videoLightHint by viewModel.videoLightHint.collectAsStateWithLifecycle()
    val pictureChip by viewModel.pictureChip.collectAsStateWithLifecycle()
    val ready = connection is RemoteConnectionState.Connected
    val streaming = mirrorState as? MirrorState.Streaming
    val ratio = if (streaming != null && streaming.height > 0) {
        streaming.width.toFloat() / streaming.height.toFloat()
    } else {
        16f / 9f
    }
    var chromeGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) {
        onDispose { viewModel.leave() }
    }
    LaunchedEffect(chromeGeneration) {
        if (chromeGeneration == 0) return@LaunchedEffect
        delay(CHROME_VISIBLE_MS)
        chromeGeneration = 0
    }
    LaunchedEffect(fullscreen) {
        if (!fullscreen) chromeGeneration = 0
    }
    BackHandler(enabled = fullscreen) { viewModel.setFullscreen(false) }
    MirrorWindowEffect(fullscreen)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)
            .then(
                if (fullscreen) {
                    Modifier
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                },
            ),
    ) {
        if (!fullscreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.screen),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                DemoChip()
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.open_remote))
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(ratio))
                .background(Color.Black),
        ) {
            MirrorVideo(
                state = mirrorState,
                stage = stage,
                pictureChip = pictureChip,
                onBind = viewModel::bindVideo,
                onLayout = viewModel::onVideoLayout,
                onTouch = viewModel::touch,
                modifier = Modifier.fillMaxSize(),
            )
            if (!fullscreen) {
                FullscreenButton(
                    onClick = { viewModel.setFullscreen(true) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            } else {
                FullscreenChrome(
                    visible = chromeGeneration > 0,
                    ready = ready,
                    onReveal = { chromeGeneration++ },
                    onKey = viewModel::sendKey,
                    onExit = { viewModel.setFullscreen(false) },
                )
            }
            PresetChip(
                preset = preset,
                onClick = {
                    viewModel.selectPreset(
                        if (preset == MirrorPreset.Smooth) MirrorPreset.Light else MirrorPreset.Smooth,
                    )
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
            if (videoLightHint) {
                Text(
                    text = stringResource(R.string.screen_video_light_hint),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (!fullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.screen_touch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.screen_drm_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                DirectionPad(enabled = ready, onKey = viewModel::sendKey)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    KeyButton(stringResource(R.string.key_back), TvKey.BACK, ready, viewModel::sendKey)
                    KeyButton(stringResource(R.string.key_home), TvKey.HOME, ready, viewModel::sendKey)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    KeyButton(stringResource(R.string.key_volume_down), TvKey.VOLUME_DOWN, ready, viewModel::sendKey)
                    KeyButton(stringResource(R.string.key_mute), TvKey.MUTE, ready, viewModel::sendKey)
                    KeyButton(stringResource(R.string.key_volume_up), TvKey.VOLUME_UP, ready, viewModel::sendKey)
                }
            }
        }
    }
}

@Composable
private fun MirrorVideo(
    state: MirrorState,
    stage: MirrorStage,
    pictureChip: PictureChip,
    onBind: (TextureView, Int, Int) -> Unit,
    onLayout: (Int, Int) -> Unit,
    onTouch: (Int, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val streaming = state as? MirrorState.Streaming
    val videoWidth = streaming?.width ?: 0
    val videoHeight = streaming?.height ?: 0
    val picture by (LocalContext.current.applicationContext as TvRemoteApplication)
        .preview.picture.collectAsStateWithLifecycle()
    Box(modifier) {
        if (BuildConfig.IS_DEMO) {
            VirtualTv(
                picture = picture,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onTouch(ScrcpyProtocol.ACTION_UP, offset.x, offset.y)
                        }
                    },
            )
        } else AndroidView(
            factory = { context -> TextureView(context) },
            update = { view -> onBind(view, videoWidth, videoHeight) },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { onLayout(it.width, it.height) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onTouch(ScrcpyProtocol.ACTION_DOWN, down.position.x, down.position.y)
                        down.consume()
                        var lastX = down.position.x
                        var lastY = down.position.y
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                lastX = change.position.x
                                lastY = change.position.y
                                change.consume()
                                if (!change.pressed) break
                                onTouch(ScrcpyProtocol.ACTION_MOVE, lastX, lastY)
                            }
                        } finally {
                            onTouch(ScrcpyProtocol.ACTION_UP, lastX, lastY)
                        }
                    }
                },
        )
        when {
            stage is MirrorStage.Failed -> StatusText(stage.message, error = true)
            pictureChip == PictureChip.Blocked -> {
                Text(
                    text = stringResource(R.string.screen_capture_hidden),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            stage !is MirrorStage.Streaming -> LoadingStage(stage)
            pictureChip == PictureChip.Paused || pictureChip == PictureChip.Static -> {
                val label = if (pictureChip == PictureChip.Paused) {
                    R.string.screen_paused_chip
                } else {
                    R.string.screen_static_chip
                }
                Text(
                    text = stringResource(label),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingStage(stage: MirrorStage) {
    val text = when (stage) {
        MirrorStage.StartingServer -> stringResource(R.string.screen_stage_server)
        MirrorStage.ConnectingVideo -> stringResource(R.string.screen_stage_video)
        MirrorStage.WaitingFirstFrame -> stringResource(R.string.screen_stage_frame)
        else -> stringResource(R.string.screen_stage_preparing)
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White)
        Text(
            text = text,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
        )
    }
}

@Composable
private fun StatusText(text: String, error: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (error) MaterialTheme.colorScheme.error else Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun PresetChip(
    preset: MirrorPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val label = if (preset == MirrorPreset.Light) R.string.screen_preset_light else R.string.screen_preset_smooth
    Text(
        text = stringResource(label),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun FullscreenButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_fullscreen),
            contentDescription = stringResource(R.string.fullscreen),
            tint = Color.White,
        )
    }
}

@Composable
private fun FullscreenChrome(
    visible: Boolean,
    ready: Boolean,
    onReveal: () -> Unit,
    onKey: (TvKey, KeyPress) -> Unit,
    onExit: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) { detectTapGestures { onReveal() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(width = 48.dp, height = 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.75f)),
        )
    }
    if (!visible) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .statusBarsPadding()
            .pointerInput(Unit) { detectTapGestures { onReveal() } }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onKey(TvKey.BACK, KeyPress.SHORT)
            },
            enabled = ready,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Text(stringResource(R.string.key_back))
        }
        TextButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onKey(TvKey.HOME, KeyPress.SHORT)
            },
            enabled = ready,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Text(stringResource(R.string.key_home))
        }
        TextButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onExit()
            },
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fullscreen_exit),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(stringResource(R.string.exit_fullscreen))
        }
    }
}

@Composable
private fun MirrorWindowEffect(fullscreen: Boolean) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(fullscreen) {
        val activity = view.context.findActivity() ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val CHROME_VISIBLE_MS = 3_000L
