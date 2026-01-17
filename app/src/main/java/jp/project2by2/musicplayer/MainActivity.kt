package jp.project2by2.musicplayer

import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

import dev.atsushieno.ktmidi.*
import dev.atsushieno.ktmidi.read
import android.content.Context

import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme

import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

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
    var currentPosition by remember { mutableStateOf(0) }
    var loopPoint by remember { mutableStateOf(0) }

    // 再生位置取得用
    LaunchedEffect(mediaPlayer, selectedFileUri) {
        while (true) {
            if (mediaPlayer.isPlaying) {
                currentPosition = mediaPlayer.currentPosition
            }
            delay(100L) // 100ミリ秒ごとに更新
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

                loopPoint = findLoopPoint(context, selectedFileUri!!)
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
            text = "Position: ${currentPosition / 1000}.${(currentPosition % 1000).toString().padStart(3, '0')} s",
            modifier = Modifier.padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall
        )

        // Loop point
        Text(
            text = "Loop point: ${loopPoint} s",
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
fun findLoopPoint(context: Context, uri: Uri): Int {
    val contentResolver = context.contentResolver

    // URIから入力ストリームを取得
    contentResolver.openInputStream(uri)?.use { inputStream ->
        // バイト列に変換
        val bytes = inputStream.readAllBytes().toList()

        // ktmidiでMIDIデータを読み込む
        val musicReader = Midi1Music()
        musicReader.read(bytes)

        // トラックごとに走査
        val tracks = musicReader.tracks
        for (track in tracks) {
            // イベントごとに走査
            val events = track.events
            for (event in events) {
                // メッセージを抜き出す
                val message = event.message

                // コントロールチェンジ111のみ取り出す
                if ((message.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC) {
                    if (message.msb == 111.toByte()) {
                        return event.deltaTime
                    }
                }
            }
        }
    }

    return 0  // なければ0ms
}