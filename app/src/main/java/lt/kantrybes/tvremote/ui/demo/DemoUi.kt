package lt.kantrybes.tvremote.ui.demo

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import lt.kantrybes.tvremote.BuildConfig
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.backend.TvPicture

private const val INTRO_PREFS = "demo_intro"
private const val INTRO_SEEN = "seen"

@Composable
internal fun DemoChip(modifier: Modifier = Modifier) {
    if (!BuildConfig.IS_DEMO) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = stringResource(R.string.demo_chip),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
internal fun DemoIntroDialog() {
    if (!BuildConfig.IS_DEMO) return
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(!introSeen(context)) }
    if (!open) return
    AlertDialog(
        onDismissRequest = {
            markIntroSeen(context)
            open = false
        },
        title = { Text(stringResource(R.string.demo_intro_title)) },
        text = { Text(stringResource(R.string.demo_intro_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    markIntroSeen(context)
                    open = false
                },
            ) {
                Text(stringResource(R.string.demo_intro_ok))
            }
        },
    )
}

@Composable
internal fun VirtualTv(picture: TvPicture, modifier: Modifier = Modifier) {
    val power by animateFloatAsState(if (picture.powerOn) 0f else 1f, label = "power")
    var volumeVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(picture.volumeTick) {
        if (picture.volumeTick == 0) return@LaunchedEffect
        volumeVisible = true
        delay(1200)
        volumeVisible = false
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101418)),
    ) {
        if (picture.home) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val cells = picture.cells
                for (row in 0 until 4) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (column in 0 until 3) {
                            val index = row * 3 + column
                            val cell = cells.getOrNull(index)
                            val focused = index == picture.focus
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(cell?.color ?: 0xFF37474F))
                                    .then(
                                        if (focused) {
                                            Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = cell?.label.orEmpty(),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(picture.appColor)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = picture.appTitle.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        picture.search?.let { query ->
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.demo_search),
                    color = Color(0xFF607D8B),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = query,
                    color = Color(0xFF102027),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (volumeVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(picture.volume / 20f)
                            .align(Alignment.BottomCenter)
                            .background(Color.White),
                    )
                }
            }
        }
        if (picture.muted) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp),
            ) {
                drawCircle(color = Color.Black.copy(alpha = 0.72f))
                drawLine(
                    color = Color.White,
                    start = androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.3f),
                    end = androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height * 0.7f),
                    strokeWidth = 6f,
                )
                drawLine(
                    color = Color.White,
                    start = androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height * 0.3f),
                    end = androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.7f),
                    strokeWidth = 6f,
                )
            }
        }
        picture.banner?.let { banner ->
            Text(
                text = banner,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (picture.preset.isNotEmpty()) {
            Text(
                text = picture.preset,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = power)),
        )
    }
}

private fun introSeen(context: Context): Boolean {
    return context.getSharedPreferences(INTRO_PREFS, Context.MODE_PRIVATE).getBoolean(INTRO_SEEN, false)
}

private fun markIntroSeen(context: Context) {
    context.getSharedPreferences(INTRO_PREFS, Context.MODE_PRIVATE).edit().putBoolean(INTRO_SEEN, true).apply()
}
