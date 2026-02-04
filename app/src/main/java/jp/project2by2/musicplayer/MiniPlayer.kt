package jp.project2by2.musicplayer

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Slider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.materialIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MiniPlayerUi(
    val title: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val loopStartMs: Long,
    val loopEndMs: Long,
)

// Bottom mini player
@Composable
fun MiniPlayerBar(
    title: String,
    isPlaying: Boolean,
    progress: Float,
    currentPositionMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
    onPlayPause: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // For seekbar slider
    var isSeeking by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(progress) }
    var isDetailsExpanded by remember { mutableStateOf(false) }

    var loopEnabled by remember { mutableStateOf(false) }
    var shuffleEnabled by remember { mutableStateOf(false) }

    // Load settings value
    LaunchedEffect(isPlaying) {
        loopEnabled = SettingsDataStore.loopEnabledFlow(context).first()
        shuffleEnabled = SettingsDataStore.shuffleEnabledFlow(context).first()
    }

    Surface(
        shape = RoundedCornerShape(0.dp),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
        onClick = { isDetailsExpanded = !isDetailsExpanded }
    ) {
        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = if (isSeeking) sliderValue else progress,
                valueRange = 0f..1f,
                onValueChange = { v ->
                    isSeeking = true
                    sliderValue = v
                },
                onValueChangeFinished = {
                    isSeeking = false
                    onSeekTo(sliderValue)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .padding(vertical = 24.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                        .clipToBounds()
                        .basicMarquee(iterations = Int.MAX_VALUE),
                    maxLines = 1
                )
                val seconds = currentPositionMs / 1000
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                Text(
                    text = String.format("%d:%02d", minutes, remainingSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                IconButton(
                    onClick = {
                        shuffleEnabled = !shuffleEnabled
                        scope.launch {
                            SettingsDataStore.setShuffleEnabled(context, shuffleEnabled)
                        }
                    }
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (shuffleEnabled) { MaterialTheme.colorScheme.primaryContainer } else { Color.Transparent },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.padding(8.dp).size(48.dp)
                        )
                    }
                }
                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(48.dp)
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(48.dp)
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(48.dp)
                    )
                }
                IconButton(
                    onClick = {
                        loopEnabled = !loopEnabled
                        scope.launch {
                            SettingsDataStore.setLoopEnabled(context, loopEnabled)
                        }
                    }
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (loopEnabled) { MaterialTheme.colorScheme.primaryContainer } else { Color.Transparent },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Loop,
                            contentDescription = null,
                            modifier = Modifier.padding(8.dp).size(48.dp)
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = isDetailsExpanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Current time: $currentPositionMs ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Loop point: $loopStartMs ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "End of track: $loopEndMs ms",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun MiniPlayerContainer(
    playbackService: PlaybackService?,
    selectedMidiFileUri: Uri?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    // 750ms（まずはここ）: 500〜1000msで調整
    val uiState = produceState<MiniPlayerUi?>(initialValue = null, key1 = playbackService, key2 = selectedMidiFileUri) {
        val service = playbackService ?: run { value = null; return@produceState }

        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            // 取得は Default（CPU寄り）へ
            val snapshot = withContext(Dispatchers.Default) {
                val lp = service.getLoopPoint()
                val duration = service.getDurationMs().coerceAtLeast(0L)
                MiniPlayerUi(
                    title = service.getCurrentTitle() ?: "No file selected",
                    isPlaying = service.isPlaying(),
                    positionMs = service.getCurrentPositionMs(),
                    durationMs = duration,
                    loopStartMs = lp?.startMs ?: 0L,
                    loopEndMs = duration,
                )
            }

            // value 代入（= Compose state更新）は Main に戻った状態で行われる
            value = snapshot

            delay(if (snapshot.isPlaying) 100L else 200L)
        }
    }.value

    if (uiState == null || selectedMidiFileUri == null) return

    // derivedStateOf（局所化）
    val progress by remember(uiState.positionMs, uiState.durationMs) {
        derivedStateOf {
            if (uiState.durationMs > 0) uiState.positionMs.toFloat() / uiState.durationMs.toFloat() else 0f
        }
    }

    MiniPlayerBar(
        title = uiState.title,
        isPlaying = uiState.isPlaying,
        progress = progress,
        currentPositionMs = uiState.positionMs,
        loopStartMs = uiState.loopStartMs,
        loopEndMs = uiState.loopEndMs,
        onPlayPause = { if (uiState.isPlaying) onPause() else onPlay() },
        onSeekTo = { ratio ->
            val ms = (ratio.coerceIn(0f, 1f) * uiState.durationMs).toLong()
            onSeekToMs(ms)
        },
        onPrevious = onPrevious,
        onNext = onNext,
    )
}
