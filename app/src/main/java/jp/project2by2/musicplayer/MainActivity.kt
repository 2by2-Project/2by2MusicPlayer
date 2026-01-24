package jp.project2by2.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Slider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

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
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFolderKey by remember { mutableStateOf<String?>(null) }
    var selectedFolderName by remember { mutableStateOf<String?>(null) }

    val midiFiles = remember { mutableStateListOf<MidiFileItem>() }
    val folderItems = remember { mutableStateListOf<FolderItem>() }

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val imagePermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(audioPermission, imagePermission)
    } else {
        arrayOf(audioPermission)
    }
    var hasAudioPermission by remember {
        mutableStateOf(hasPermission(context, audioPermission))
    }
    var hasImagePermission by remember {
        mutableStateOf(hasPermission(context, imagePermission))
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAudioPermission = results[audioPermission] == true
        hasImagePermission = results[imagePermission] == true
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

    LaunchedEffect(hasAudioPermission, hasImagePermission) {
        if (!hasAudioPermission) return@LaunchedEffect
        midiFiles.clear()
        midiFiles.addAll(queryMidiFiles(context))
        folderItems.clear()
        folderItems.addAll(buildFolderItems(context, midiFiles, hasImagePermission))
    }

    LaunchedEffect(Unit) {
        if (!hasAudioPermission) {
            storagePermissionLauncher.launch(permissionsToRequest)
        }
    }

    // Back handler
    BackHandler(enabled = selectedFolderKey != null || isSearchActive) {
        when {
            isSearchActive -> {
                isSearchActive = false
                searchQuery = ""
            }
            selectedFolderKey != null -> {
                selectedFolderKey = null
                selectedFolderName = null
            }
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
            modifier = modifier.padding(horizontal = 0.dp, vertical = 0.dp),
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
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Main screen start
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selectedFolderKey != null) {
                        IconButton(
                            onClick = {
                                selectedFolderKey = null
                                selectedFolderName = null
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                title = {
                    if (selectedFolderKey != null) {
                        Text(
                            text = selectedFolderName ?: "Unknown",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.logo_image),
                            contentDescription = "App Logo",
                            modifier = Modifier.height(48.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    }
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
                if (isSearchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search by file name") },
                        singleLine = true
                    )
                }
                if (!hasAudioPermission) {
                    ElevatedButton(onClick = { storagePermissionLauncher.launch(permissionsToRequest) }) {
                        Text("Grant storage permission")
                    }
                } else if (midiFiles.isEmpty()) {
                    Text(
                        text = "No .mid files found",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (isSearchActive && searchQuery.isNotBlank()) {
                    val filtered = midiFiles.filter {
                        it.title.contains(searchQuery, ignoreCase = true)
                    }
                    MidiFileList(
                        items = filtered,
                        selectedUri = selectedMidiFileUri,
                        onItemClick = { handleMidiTap(it) }
                    )
                } else if (selectedFolderKey == null) {
                    FolderGrid(
                        items = folderItems,
                        onFolderClick = { folder ->
                            selectedFolderKey = folder.key
                            selectedFolderName = folder.name
                        }
                    )
                } else {
                    val filtered = midiFiles.filter { it.folderKey == selectedFolderKey }
                    MidiFileList(
                        items = filtered,
                        selectedUri = selectedMidiFileUri,
                        onItemClick = { handleMidiTap(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MidiFileList(
    items: List<MidiFileItem>,
    selectedUri: Uri?,
    onItemClick: (Uri) -> Unit
) {
    if (items.isEmpty()) {
        Text(
            text = "No matching files",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 72.dp)
    ) {
        items(items, key = { it.uri }) { item ->
            MidiFileRow(
                item = item,
                isSelected = item.uri == selectedUri,
                onClick = { onItemClick(item.uri) }
            )
        }
    }
}

@Composable
private fun MidiFileRow(
    item: MidiFileItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val background = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(0.25f)
    } else {
        Color.Transparent
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onBackground
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 0.dp, vertical = 0.dp)
            .background(background, RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(16.dp)
        ) {
            Text(
                text = item.title,
                maxLines = 1,
                color = contentColor
            )
            if (item.folderName.isNotBlank()) {
                Text(
                    text = item.folderName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.5f),
                    maxLines = 1,
                    color = contentColor
                )
            }
        }
        Text(
            text = formatDuration(item.durationMs),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
            color = contentColor
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderGrid(
    items: List<FolderItem>,
    onFolderClick: (FolderItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(items, key = { it.key }) { folder ->
            FolderCard(folder = folder, onClick = { onFolderClick(folder) })
        }
    }
}

@Composable
private fun FolderCard(
    folder: FolderItem,
    onClick: () -> Unit
) {
    val coverBitmap = rememberCoverBitmap(folder.coverUri)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = folder.name,
                modifier = Modifier.padding(12.dp),
                maxLines = 1
            )
        }
    }
}

private data class MidiFileItem(
    val uri: Uri,
    val title: String,
    val folderName: String,
    val folderKey: String,
    val durationMs: Long
)

private data class FolderItem(
    val key: String,
    val name: String,
    val coverUri: Uri?
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
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} ASC"

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
            val folderKey = extractFolderKey(relativePath, dataPath)
            val folderName = extractFolderName(folderKey)
            val uri = ContentUris.withAppendedId(collection, id)
            results.add(MidiFileItem(uri, name, folderName, folderKey, duration))
        }
    }
    return results
}

private fun extractFolderKey(relativePath: String?, dataPath: String?): String {
    relativePath?.let {
        val trimmed = it.trimEnd('/', '\\')
        if (trimmed.isNotBlank()) {
            return trimmed.replace('\\', '/')
        }
    }
    dataPath?.let {
        val normalized = it.replace('\\', '/')
        val parent = normalized.substringBeforeLast('/', "")
        if (parent.isNotBlank()) {
            return parent
        }
    }
    return ""
}

private fun extractFolderName(folderKey: String): String {
    if (folderKey.isBlank()) return ""
    return folderKey.substringAfterLast('/', folderKey.substringAfterLast('\\', folderKey))
}

private fun buildFolderItems(
    context: Context,
    items: List<MidiFileItem>,
    hasImagePermission: Boolean
): List<FolderItem> {
    val grouped = items.groupBy { it.folderKey }
    val results = mutableListOf<FolderItem>()
    for ((key, _) in grouped) {
        val name = extractFolderName(key).ifBlank { "Unknown" }
        val coverUri = if (hasImagePermission) findCoverImageUri(context, key) else null
        results.add(FolderItem(key = key, name = name, coverUri = coverUri))
    }
    return results.sortedBy { it.name.lowercase() }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun findCoverImageUri(context: Context, folderKey: String): Uri? {
    if (folderKey.isBlank()) return null
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
        MediaStore.Files.FileColumns.DATA
    )
    val names = listOf(
        "cover.jpg",
        "cover.png",
        "folder.jpg",
        "folder.png",
        "Cover.jpg",
        "Cover.png"
    )

    val selection = if (Build.VERSION.SDK_INT >= 29) {
        val nameClause = names.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME}=?" }
        "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ($nameClause)"
    } else {
        val nameClause = names.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE ?" }
        "($nameClause)"
    }

    val selectionArgs = if (Build.VERSION.SDK_INT >= 29) {
        arrayOf(folderKey.trimEnd('/') + "/") + names.toTypedArray()
    } else {
        val base = folderKey.trimEnd('/')
        names.map { "%$base/$it" }.toTypedArray()
    }

    context.contentResolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(idColumn)
            return ContentUris.withAppendedId(collection, id)
        }
    }
    return null
}

@Composable
private fun rememberCoverBitmap(uri: Uri?): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    val bitmapState = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, uri) {
        if (uri == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val input: InputStream? = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
            input?.use {
                val bmp = android.graphics.BitmapFactory.decodeStream(it)
                bmp?.asImageBitmap()
            }
        }
    }
    return bitmapState.value
}
