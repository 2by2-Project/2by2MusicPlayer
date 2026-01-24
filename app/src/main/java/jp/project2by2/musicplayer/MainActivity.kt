package jp.project2by2.musicplayer

import android.Manifest
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.alpha
import androidx.core.content.ContextCompat

import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme

import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            _2by2MusicPlayerTheme {
                MusicPlayerMainScreen()
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerMainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var selectedMidiFileUri by remember { mutableStateOf<Uri?>(null) }

    // Positions
    var currentPositionString by remember { mutableStateOf("0:00") }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var loopStartMs: Long by remember { mutableStateOf(0L) }
    var loopEndMs: Long by remember { mutableStateOf(0L) }

    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var isBound by remember { mutableStateOf(false) }

    // Playing state (for bottom bar)
    var isPlaying by remember { mutableStateOf(false) }
    val progress = if (loopEndMs > 0) currentPositionMs.toFloat() / loopEndMs.toFloat() else 0f
    var isDetailsExpanded by remember { mutableStateOf(false) }

    val midiFiles = remember { mutableStateListOf<MidiFileItem>() }

    val storagePermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasStoragePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, storagePermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasStoragePermission = granted
    }

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

        service.getCurrentUriString()?.let { uriString ->
            selectedMidiFileUri = Uri.parse(uriString)
            isPlaying = service.isPlaying()
        }

        while (true) {
            currentPositionMs = service.getCurrentPositionMs()
            loopStartMs = service.getLoopPoint()?.startMs ?: 0L
            loopEndMs = service.getDurationMs()

            // Update current position text
            val seconds = currentPositionMs / 1000
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            currentPositionString = String.format("%d:%02d", minutes, remainingSeconds)

            delay(50L)
        }
    }

    LaunchedEffect(hasStoragePermission) {
        if (!hasStoragePermission) return@LaunchedEffect
        midiFiles.clear()
        midiFiles.addAll(queryMidiFiles(context))
    }

    LaunchedEffect(Unit) {
        if (!hasStoragePermission) {
            storagePermissionLauncher.launch(storagePermission)
        }
    }

    fun handleMidiTap(uri: Uri) {
        val cacheSoundFontFile = File(context.cacheDir, "soundfont.sf2")
        if (!cacheSoundFontFile.exists()) {
            Toast.makeText(context, "SoundFont is not set!", Toast.LENGTH_SHORT).show()
            return
        }
        selectedMidiFileUri = uri
        isPlaying = false
        try {
            val ok = playbackService?.loadMidi(uri.toString()) ?: false
            if (!ok) {
                Toast.makeText(context, "Failed to load MIDI or SoundFont", Toast.LENGTH_SHORT).show()
                return
            }
            playbackService?.play()
            isPlaying = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Bottom mini player
    @Composable
    fun MiniPlayerBar(
        title: String,
        isPlaying: Boolean,
        progress: Float,
        onPlayPause: () -> Unit,
        onOpenPlayer: () -> Unit,
        onSeekBar: (Float) -> Unit,
        modifier: Modifier = Modifier
    ) {
        // For seekbar slider
        var isSeeking by remember { mutableStateOf(false) }
        var sliderValue by remember { mutableStateOf(progress) }

        Surface(
            modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            onClick = onOpenPlayer
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
                        val durationMs = loopEndMs
                        if (durationMs > 0L) {
                            val newPosMs = (sliderValue.coerceIn(0f, 1f) * durationMs).toLong()
                            playbackService?.setCurrentPositionMs(newPosMs)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                        .height(32.dp)
                        .padding(vertical = 24.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, Modifier.weight(1f), maxLines = 1)
                    Text(
                        text = currentPositionString,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
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
                Spacer(modifier.height(24.dp))
            }
        }
    }

    // Main screen start
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "2by2 Music Player",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = selectedMidiFileUri != null) {
                MiniPlayerBar(
                    title = playbackService?.getCurrentTitle() ?: "No file selected",
                    isPlaying = isPlaying,
                    progress = progress,
                    onPlayPause = {
                        if (!playbackService!!.isPlaying()) {
                            playbackService?.play()
                            isPlaying = true
                        } else {
                            playbackService?.pause()
                            isPlaying = false
                        }
                    },
                    onOpenPlayer = { isDetailsExpanded = !isDetailsExpanded },
                    onSeekBar = { newValue ->
                        val newPosMs = (newValue.coerceIn(0f, 1f) * loopEndMs).toLong()
                        playbackService?.setCurrentPositionMs(newPosMs)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 0.dp, vertical = 12.dp)
            ) {
                if (!hasStoragePermission) {
                    ElevatedButton(onClick = { storagePermissionLauncher.launch(storagePermission) }) {
                        Text("Grant storage permission")
                    }
                } else if (midiFiles.isEmpty()) {
                    Text(
                        text = "No .mid files found",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 72.dp)
                    ) {
                        items(midiFiles, key = { it.uri }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { handleMidiTap(item.uri) }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = item.title,
                                        maxLines = 1
                                    )
                                    if (item.folderName.isNotBlank()) {
                                        Text(
                                            text = item.folderName,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.alpha(0.5f),
                                            maxLines = 1
                                        )
                                    }
                                }
                                Text(
                                    text = formatDuration(item.durationMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class MidiFileItem(
    val uri: Uri,
    val title: String,
    val folderName: String,
    val durationMs: Long
)

private fun queryMidiFiles(context: Context): List<MidiFileItem> {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.DURATION,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
        MediaStore.Files.FileColumns.DATA
    )
    val selection = (
        "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR " +
            "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        )
    val selectionArgs = arrayOf(
        "audio/midi",
        "audio/x-midi",
        "%.mid",
        "%.midi"
    )
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

    val results = mutableListOf<MidiFileItem>()
    context.contentResolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
        val relativePathColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
        val dataColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn) ?: continue
            val duration = cursor.getLong(durationColumn)
            val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null
            val dataPath = if (dataColumn >= 0) cursor.getString(dataColumn) else null
            val folderName = extractFolderName(relativePath, dataPath)
            val uri = ContentUris.withAppendedId(collection, id)
            results.add(MidiFileItem(uri, name, folderName, duration))
        }
    }
    return results
}

private fun extractFolderName(relativePath: String?, dataPath: String?): String {
    relativePath?.let {
        val trimmed = it.trimEnd('/', '\\')
        if (trimmed.isNotBlank()) {
            return trimmed.substringAfterLast('/', trimmed.substringAfterLast('\\', trimmed))
        }
    }
    dataPath?.let {
        val normalized = it.replace('\\', '/')
        val parent = normalized.substringBeforeLast('/', "")
        if (parent.isNotBlank()) {
            return parent.substringAfterLast('/')
        }
    }
    return ""
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}