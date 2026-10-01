package lt.kantrybes.tvremote.ui.screen

import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.data.scrcpy.MirrorState
import lt.kantrybes.tvremote.data.scrcpy.ScrcpyProtocol
import lt.kantrybes.tvremote.ui.remote.DirectionPad
import lt.kantrybes.tvremote.ui.remote.KeyButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenScreen(
    onClose: () -> Unit,
    viewModel: ScreenViewModel = viewModel(),
) {
    val mirrorState by viewModel.mirrorState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val ready = connection is RemoteConnectionState.Connected
    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen)) },
                actions = {
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.open_remote))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VideoFrame(
                state = mirrorState,
                onSurface = viewModel::setSurface,
                onTouch = viewModel::touch,
                modifier = Modifier.fillMaxWidth(),
            )
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

@Composable
private fun VideoFrame(
    state: MirrorState,
    onSurface: (Surface?) -> Unit,
    onTouch: (Int, Float, Float, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val streaming = state as? MirrorState.Streaming
    val ratio = if (streaming != null && streaming.height > 0) {
        streaming.width.toFloat() / streaming.height.toFloat()
    } else {
        16f / 9f
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            texture: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            onSurface(Surface(texture))
                        }

                        override fun onSurfaceTextureSizeChanged(
                            texture: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                            onSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onTouch(
                            ScrcpyProtocol.ACTION_DOWN,
                            down.position.x,
                            down.position.y,
                            size.width,
                            size.height,
                        )
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
                                onTouch(
                                    ScrcpyProtocol.ACTION_MOVE,
                                    lastX,
                                    lastY,
                                    size.width,
                                    size.height,
                                )
                            }
                        } finally {
                            onTouch(ScrcpyProtocol.ACTION_UP, lastX, lastY, size.width, size.height)
                        }
                    }
                },
        )
        when (state) {
            MirrorState.StartingServer -> Text(stringResource(R.string.screen_starting))
            MirrorState.Connecting, MirrorState.Idle -> Text(stringResource(R.string.screen_connecting))
            is MirrorState.Failed -> Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            is MirrorState.Streaming -> Unit
        }
    }
}
