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

import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI

import dev.atsushieno.ktmidi.*
import dev.atsushieno.ktmidi.read
import android.content.Context
import android.content.Intent
import android.widget.Toast

import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme

import kotlinx.coroutines.delay
import java.io.File

// 音楽をループさせる用
data class LoopPoint(
    // Milliseconds
    var startMs: Long = 0L,
    var endMs: Long = -1L,

    // MIDI Tick
    var startTick: Int = 0,
    var endTick: Int = -1,
)

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

    // Current uri and handles
    var handles by remember { mutableStateOf<MidiHandles?>(null) }
    var selectedMidiFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedSoundFontUri by remember { mutableStateOf<Uri?>(null) }

    // Positions
    var currentPositionMs by remember { mutableStateOf(0L) }
    var loopStartMs: Long by remember { mutableStateOf(0L) }
    var loopEndMs: Long by remember { mutableStateOf(0L) }

    // Sync
    var syncProc: BASS.SYNCPROC? = null
    var syncHandle: Int = 0

    // Init BASSMIDI
    LaunchedEffect(Unit) {
        val ok = bassInit()
        if (!ok) {
            Toast.makeText(context, "BASS_Init failed", Toast.LENGTH_SHORT).show()
        }
    }

    // Terminate BASSMIDI
    DisposableEffect(Unit) {
        onDispose {
            handles?.let { bassRelease(it) }
            bassTerminate()
        }
    }

    // 再生位置取得用
    LaunchedEffect(selectedMidiFileUri) {
        while (handles != null) {
            // Convert bytes to seconds
            val bytes = BASS.BASS_ChannelGetPosition(handles!!.stream, BASS.BASS_POS_BYTE)
            val secs = BASS.BASS_ChannelBytes2Seconds(handles!!.stream, bytes)
            if (isBassPlaying(handles!!.stream) == BASS.BASS_ACTIVE_PLAYING) {
                currentPositionMs = (secs * 1000.0).toLong()
            }

            delay(50L) // 50ミリ秒ごとに更新
        }
    }

    // MIDI file picker
    val MidiFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            if (selectedSoundFontUri == null) {
                Toast.makeText(context, "SoundFont is not selected!", Toast.LENGTH_SHORT).show()
                return@let
            }
            selectedMidiFileUri = it
            try {
                // Load MIDI file
                val cacheMidiFile = File(context.cacheDir, "midi.mid")
                context.contentResolver.openInputStream(selectedMidiFileUri!!)?.use { input ->
                    cacheMidiFile.outputStream().use { output -> input.copyTo(output) }
                }
                val midiPath = cacheMidiFile.absolutePath

                // Load SoundFont file
                val cacheSoundFontFile = File(context.cacheDir, "soundfont.sf2")
                context.contentResolver.openInputStream(selectedSoundFontUri!!)?.use { input ->
                    cacheSoundFontFile.outputStream().use { output -> input.copyTo(output) }
                }
                val sfPath = cacheSoundFontFile.absolutePath

                // Load MIDI file with SoundFont
                handles?.let { bassRelease(it) }
                handles = bassLoadMidiWithSoundFont(midiPath, sfPath)

                // ループポイントを検知
                val loopPoint = findLoopPoint(context, selectedMidiFileUri!!)

                // ミリ秒の位置をテキストに反映
                loopStartMs = loopPoint.startMs

                // Convert bytes to seconds
                val bytes = BASS.BASS_ChannelGetLength(handles!!.stream, BASS.BASS_POS_BYTE)
                val secs = BASS.BASS_ChannelBytes2Seconds(handles!!.stream, bytes) * 1000
                loopEndMs = secs.toLong()  // loopPoint.endMs

                // Set the loop flag
                BASS.BASS_ChannelFlags(handles!!.stream, BASS.BASS_SAMPLE_LOOP, BASS.BASS_SAMPLE_LOOP)

                // Enable decay flag
                BASS.BASS_ChannelFlags(handles!!.stream, BASSMIDI.BASS_MIDI_DECAYSEEK, BASSMIDI.BASS_MIDI_DECAYSEEK)
                BASS.BASS_ChannelFlags(handles!!.stream, BASSMIDI.BASS_MIDI_DECAYEND, BASSMIDI.BASS_MIDI_DECAYEND)

                // Setup BASS sync feature for looping
                syncProc = null
                if (syncHandle != 0) {
                    BASS.BASS_ChannelRemoveSync(handles!!.stream, syncHandle)
                    syncHandle = 0
                }
                syncProc = BASS.SYNCPROC { handle, channel, data, user ->
                    BASS.BASS_ChannelSetPosition(
                        handles!!.stream,
                        loopPoint.startTick.toLong(),
                        BASSMIDI.BASS_POS_MIDI_TICK or BASSMIDI.BASS_MIDI_DECAYSEEK
                    )
                }
                syncHandle = BASS.BASS_ChannelSetSync(
                    handles!!.stream,
                    BASS.BASS_SYNC_MIXTIME,
                    bytes,
                    syncProc,
                    0
                )
            } catch (e: Exception) {
                // ファイル読み込み中のエラー処理
                e.printStackTrace()
            }
        }
    }

    // SoundFont picker
    val SoundFontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedSoundFontUri = it  // Set soundfont uri
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = selectedMidiFileUri?.lastPathSegment?.split("/")?.last() ?: "No file selected",
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
            ElevatedButton(onClick = {
                MidiFilePicker.launch(
                    arrayOf(
                        "audio/midi",
                        "audio/x-midi",
                        "audio/mid",
                        "application/x-midi"
                    )
                )
            }) {
                Text("Browse")
            }
            ElevatedButton(
                onClick = { handles?.let { bassPlay(it.stream) } },
                enabled = handles != null
            ) { Text("Play") }
            ElevatedButton(
                onClick = { handles?.let { bassStop(it.stream) } },
                enabled = handles != null
            ) { Text("Stop") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ElevatedButton(onClick = { SoundFontPicker.launch("application/octet-stream") }) {
                Text("Load SoundFont")
            }
        }
    }
}

// CC #111 のイベント時間を探す関数
fun findLoopPoint(context: Context, uri: Uri): LoopPoint {
    val contentResolver = context.contentResolver

    // Loop point
    val loopPoint = LoopPoint()

    // Get stream from uri
    contentResolver.openInputStream(uri)?.use { inputStream ->
        // Read bytes from input stream
        val bytes = inputStream.readAllBytes().toList()

        // Read MIDI file using ktmidi
        val music = Midi1Music().apply { read(bytes) }

        // Detect CC #111 loop point (RPG Maker)
        for (track in music.tracks) {
            var tick = 0
            for (e in track.events) {
                tick += e.deltaTime
                val m = e.message
                val isCC = ((m.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC)
                if (isCC && m.msb.toInt() == 111) {
                    loopPoint.startTick = tick
                    loopPoint.startMs = music.getTimePositionInMillisecondsForTick(tick).toLong()
                }
            }
        }

        // Detect end of track point
        for (track in music.tracks) {
            var tick = 0
            for (e in track.events) tick += e.deltaTime
            loopPoint.endTick = tick
            loopPoint.endMs = music.getTimePositionInMillisecondsForTick(tick).toLong()
        }
    }

    return loopPoint
}

data class MidiHandles(
    val stream: Int,
    val font: Int
)

fun bassInit(): Boolean {
    return BASS.BASS_Init(-1, 44100, 0)
}

fun bassPlay(stream: Int) {
    BASS.BASS_ChannelPlay(stream, false)
}

fun bassStop(stream: Int) {
    BASS.BASS_ChannelStop(stream)
    BASS.BASS_ChannelSetPosition(stream, 0, BASS.BASS_POS_BYTE)
}

fun bassRelease(handles: MidiHandles) {
    BASS.BASS_StreamFree(handles.stream)
    BASSMIDI.BASS_MIDI_FontFree(handles.font)
}

fun bassTerminate() {
    BASS.BASS_Free()
}

fun isBassPlaying(stream: Int): Int {
    return BASS.BASS_ChannelIsActive(stream)
}

fun bassLoadMidiWithSoundFont(midiPath: String, sf2Path: String): MidiHandles {
    val soundFontHandle = BASSMIDI.BASS_MIDI_FontInit(sf2Path, 0)

    // flags は必要に応じて（ループ等は別途）
    val stream = BASSMIDI.BASS_MIDI_StreamCreateFile(midiPath, 0, 0, 0, 0)

    // BASS_MIDI_FONT オブジェクトを作成します。
    // preset と bank に -1 を指定すると、すべてのプリセットとバンクが使用されます。
    val fonts = arrayOf(
        BASSMIDI.BASS_MIDI_FONT().apply {
            font = soundFontHandle
            preset = -1  // All presets
            bank = 0
        }
    )
    BASSMIDI.BASS_MIDI_StreamSetFonts(stream, fonts, 1)

    return MidiHandles(stream, soundFontHandle)
}
