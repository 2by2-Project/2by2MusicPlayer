package jp.project2by2.musicplayer

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import dev.atsushieno.ktmidi.*
import dev.atsushieno.ktmidi.read
import android.content.Context

import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme

import kotlinx.coroutines.delay

// 音楽をループさせる用
data class LoopPoint(val startMs: Long = -1L, val endMs: Long = -1L)

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            _2by2MusicPlayerTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "2by2 Music Player",
                                    // TopAppBar内で中央揃えにしたい場合
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    MusicPlayerMainScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MusicPlayerMainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // MediaPlayer
    val mediaPlayer = remember { MediaPlayer() }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var loopStartMs: Long by remember { mutableStateOf(0L) }
    var loopEndMs: Long by remember { mutableStateOf(0L) }

    // 再生位置取得用
    LaunchedEffect(mediaPlayer, selectedFileUri) {
        while (true) {
            if (mediaPlayer.isPlaying) {
                currentPositionMs = mediaPlayer.currentPosition.toLong()

                // ループ処理
                if (loopEndMs != -1L && currentPositionMs >= loopEndMs) {
                    if (loopStartMs != -1L) {
                        // ループポイントがあれば飛ぶ（-50msぐらい前）
                        mediaPlayer.seekTo(loopStartMs.toInt() - 50)
                    } else {
                        // ループポイントがなければ最初へ
                        mediaPlayer.seekTo(0)
                    }
                }
            }

            delay(50L) // 50ミリ秒ごとに更新
        }
    }

    // アプリが閉じられたとき
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer.release()
        }
    }

    // File picker
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            try {
                mediaPlayer.reset()
                mediaPlayer.setDataSource(context, it)
                mediaPlayer.prepareAsync()

                // ループポイントを検知
                val loopPoint = findLoopPointMs(context, selectedFileUri!!)
                loopStartMs = loopPoint.startMs
                loopEndMs = loopPoint.endMs

                // 再生が完了したとき
                /* mediaPlayer.setOnCompletionListener { mp ->
                    // ループポイントが検出されればそこまで戻る
                    if (loopPointMs < 0L) {
                        currentPositionMs = loopPointMs
                    } else {
                        currentPositionMs = 0L  // ループポイントがなければ最初に戻る
                    }

                    try {
                        mp.stop()
                        mp.prepareAsync()
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                    }
                } */
            } catch (e: Exception) {
                // ファイル読み込み中のエラー処理
                e.printStackTrace()
            }
        }
    }
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = selectedFileUri?.lastPathSegment?.split("/")?.last() ?: "No file selected",
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // --- 追加：再生時間を表示するText ---
        Text(
            text = "Position: $currentPositionMs ms",
            modifier = Modifier.padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall
        )

        // Loop point
        Text(
            text = "Loop point: $loopStartMs ms",
            modifier = Modifier.padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall
        )

        // End point
        Text(
            text = "End of track: $loopEndMs ms",
            modifier = Modifier.padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall
        )

        // --- ボタンのレイアウトをRowに変更 ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ElevatedButton(onClick = { launcher.launch("audio/midi") }) {
                Text("Browse")
            }
            ElevatedButton(
                onClick = {
                    if (selectedFileUri != null && !mediaPlayer.isPlaying) {
                        mediaPlayer.start()
                    }
                },
                enabled = selectedFileUri != null
            ) {
                Text("Play")
            }
            ElevatedButton(
                onClick = {
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.stop()
                        try {
                            mediaPlayer.prepareAsync()
                        } catch (e: IllegalStateException) {
                            e.printStackTrace()
                        }
                    }
                },
                enabled = selectedFileUri != null
            ) {
                Text("Stop")
            }
        }
    }
}

// CC #111 のイベント時間を探す関数
fun findLoopPointMs(context: Context, uri: Uri): LoopPoint {
    val contentResolver = context.contentResolver

    // Loop points
    var loopPointMs : Long = -1L
    var endOfTrackMs : Long = -1L

    // Get stream from uri
    contentResolver.openInputStream(uri)?.use { inputStream ->
        // Read bytes from input stream
        val bytes = inputStream.readAllBytes().toList()

        // Read MIDI file using ktmidi
        val music = Midi1Music().apply { read(bytes) }

        // Detect CC #111 loop point (RPG Maker)
        var loopPointTick: Int? = null
        for (track in music.tracks) {
            var tick = 0
            for (e in track.events) {
                tick += e.deltaTime
                val m = e.message
                val isCC = ((m.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC)
                if (isCC && m.msb.toInt() == 111) {
                    loopPointTick = when (loopPointTick) {
                        null -> tick
                        else -> minOf(loopPointTick!!, tick)
                    }
                }
            }
        }
        if (loopPointTick != null) {
            // For detect SMPTE division
            val hasSMPTEDivision = music.deltaTimeSpec < 0

            // Convert tick to ms
            loopPointMs = if (hasSMPTEDivision) {
                (Midi1Music.getSmpteDurationInSeconds(
                    music.deltaTimeSpec,
                    loopPointTick
                ) * 1000.0).toLong()
            } else {
                music.getTimePositionInMillisecondsForTick(loopPointTick).toLong()
            }
        }

        // Detect end of track point
        var endOfTrackTick = -1
        for (track in music.tracks) {
            var tick = 0
            for (e in track.events) tick += e.deltaTime
            endOfTrackTick = maxOf(endOfTrackTick, tick)
        }
        endOfTrackMs = music.getTimePositionInMillisecondsForTick(endOfTrackTick).toLong()
    }

    return LoopPoint(loopPointMs, endOfTrackMs)
}