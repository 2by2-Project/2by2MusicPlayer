package jp.project2by2.musicplayer

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
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.widget.Toast

import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme

import kotlinx.coroutines.delay
import java.io.File

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

    var selectedMidiFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedSoundFontUri by remember { mutableStateOf<Uri?>(null) }

    // Positions
    var currentPositionMs by remember { mutableStateOf(0L) }
    var loopStartMs: Long by remember { mutableStateOf(0L) }
    var loopEndMs: Long by remember { mutableStateOf(0L) }

    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var isBound by remember { mutableStateOf(false) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as? PlaybackService.LocalBinder
                playbackService = binder?.getService()
                isBound = playbackService != null
            }

            override fun onServiceDisconnected(name: ComponentName) {
                playbackService = null
                isBound = false
            }
        }
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, PlaybackService::class.java)
        context.startService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            if (isBound) {
                context.unbindService(serviceConnection)
            }
        }
    }

    LaunchedEffect(playbackService) {
        val service = playbackService ?: return@LaunchedEffect
        while (true) {
            currentPositionMs = service.getCurrentPositionMs()
            loopStartMs = service.getLoopPoint()?.startMs ?: 0L
            loopEndMs = service.getDurationMs()
            delay(50L)
        }
    }

    // MIDI file picker
    val MidiFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val cacheSoundFontFile = File(context.cacheDir, "soundfont.sf2")
            val sfPath = cacheSoundFontFile.absolutePath
            if (!File(sfPath).exists()) {
                Toast.makeText(context, "SoundFont is not set!", Toast.LENGTH_SHORT).show()
                return@let
            }
            selectedMidiFileUri = it
            try {
                val ok = playbackService?.loadMidi(selectedMidiFileUri!!.toString()) ?: false
                if (!ok) {
                    Toast.makeText(context, "Failed to load MIDI or SoundFont", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // SoundFont picker
    val SoundFontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedSoundFontUri = it

            val cacheSoundFontFile = File(context.cacheDir, "soundfont.sf2")
            context.contentResolver.openInputStream(selectedSoundFontUri!!)?.use { input ->
                cacheSoundFontFile.outputStream().use { output -> input.copyTo(output) }
            }
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

        Text(
            text = "Position: $currentPositionMs ms",
            modifier = Modifier.padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "Loop point: $loopStartMs ms",
            modifier = Modifier.padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "End of track: $loopEndMs ms",
            modifier = Modifier.padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall
        )

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
                onClick = { playbackService?.play() },
                enabled = playbackService != null
            ) { Text("Play") }
            ElevatedButton(
                onClick = { playbackService?.stop() },
                enabled = playbackService != null
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
