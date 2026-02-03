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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class MainActivity : ComponentActivity() {
    private var externalOpenUri by mutableStateOf<Uri?>(null)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleViewIntent(intent)
        enableEdgeToEdge()
        setContent {
            _2by2MusicPlayerTheme {
                MusicPlayerMainScreen(
                    externalOpenUri = externalOpenUri,
                    onExternalOpenConsumed = { externalOpenUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        val type = (intent.type ?: contentResolver.getType(uri) ?: "").lowercase()
        val path = uri.toString().lowercase()
        val isMidi = type in setOf("audio/midi", "audio/x-midi", "application/midi", "application/x-midi")
                || path.endsWith(".mid") || path.endsWith(".midi")
        if (!isMidi) return

        externalOpenUri = uri
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerMainScreen(
    externalOpenUri: Uri? = null,
    onExternalOpenConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedMidiFileUri by remember { mutableStateOf<Uri?>(null) }

    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var isBound by remember { mutableStateOf(false) }

    // Playing state (for bottom bar)
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFolderKey by remember { mutableStateOf<String?>(null) }
    var selectedFolderName by remember { mutableStateOf<String?>(null) }
    var selectedFolderCoverUri by remember { mutableStateOf<Uri?>(null) }

    val midiFiles = remember { mutableStateListOf<MidiFileItem>() }
    val folderItems = remember { mutableStateListOf<FolderItem>() }

    // Media3
    val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
    controllerFuture.addListener({
        // MediaController is available here with controllerFuture.get()
    }, MoreExecutors.directExecutor())

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
        arrayOf(
            audioPermission,
            imagePermission,
        )
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
        }
    }

    LaunchedEffect(hasAudioPermission, hasImagePermission) {
        if (!hasAudioPermission) return@LaunchedEffect

        val (files, folders) = withContext(Dispatchers.IO) {
            val f = queryMidiFiles(context)
            val d = buildFolderItems(context, f, hasImagePermission)
            f to d
        }

        midiFiles.clear()
        midiFiles.addAll(files)

        folderItems.clear()
        folderItems.addAll(folders)
    }

    LaunchedEffect(Unit) {
        val needsAnyPermission = if (Build.VERSION.SDK_INT >= 33) {
            !hasAudioPermission || !hasImagePermission
        } else {
            !hasAudioPermission
        }
        if (needsAnyPermission) {
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

        // Set artist and cover uri
        val service = playbackService
        if (service == null) {
            Toast.makeText(context, "Playback service is not ready", Toast.LENGTH_SHORT).show()
            return
        }
        service.currentArtist = selectedFolderName
        service.currentArtworkUri = selectedFolderCoverUri

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                service.loadMidi(uri.toString())
            }
            if (!ok) {
                Toast.makeText(context, "Failed to load MIDI or SoundFont", Toast.LENGTH_SHORT).show()
                return@launch
            }
            controllerFuture.addListener(
                {
                    runCatching { controllerFuture.get().play() }
                        .onFailure {
                            Toast.makeText(context, "Failed to start playback", Toast.LENGTH_SHORT).show()
                        }
                },
                MoreExecutors.directExecutor()
            )
        }
    }

    LaunchedEffect(externalOpenUri, playbackService) {
        val uri = externalOpenUri ?: return@LaunchedEffect
        if (playbackService == null) return@LaunchedEffect
        handleMidiTap(uri)          // 既存の再生処理へ
        onExternalOpenConsumed()    // 二重再生防止
    }

    // Focus requester for search bar
    val focusRequesterSearch = remember { FocusRequester() }

    // Main screen start
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)
                ),
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
                            modifier = Modifier.fillMaxWidth()
                                .clipToBounds()
                                .basicMarquee(Int.MAX_VALUE),
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
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isSearchActive) { MaterialTheme.colorScheme.primaryContainer } else { Color.Transparent },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                },
                modifier = Modifier.zIndex(1f),
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selectedMidiFileUri != null,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) +
                        fadeIn(animationSpec = tween(400)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) +
                        fadeOut(animationSpec = tween(400))
            ) {
                MiniPlayerContainer(
                    playbackService = playbackService,
                    selectedMidiFileUri = selectedMidiFileUri,
                    onPlay = { controllerFuture.get().play() },
                    onPause = { controllerFuture.get().pause() },
                    onSeekToMs = { ms -> controllerFuture.get().seekTo(ms) },
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
                modifier = Modifier.fillMaxSize()
            ) {
                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(200)) +
                        fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(200)) +
                        fadeOut(animationSpec = tween(200))
                ) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .zIndex(0f)
                                .focusRequester(focusRequesterSearch),
                            placeholder = { Text("Search by file name") },
                            singleLine = true
                        )
                        LaunchedEffect(Unit) {
                            focusRequesterSearch.requestFocus()
                        }
                    }
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
                } else {
                    val baseItems = if (selectedFolderKey != null) {
                        midiFiles.filter { it.folderKey == selectedFolderKey }
                    } else {
                        midiFiles
                    }
                    val isSearching = isSearchActive && searchQuery.isNotBlank()
                    val filtered = if (isSearching) {
                        baseItems.filter { it.title.contains(searchQuery, ignoreCase = true) }
                    } else {
                        baseItems
                    }
                    val screen = when {
                        isSearching -> BrowseScreen.Search
                        selectedFolderKey == null -> BrowseScreen.Folders
                        else -> BrowseScreen.Files
                    }
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            when {
                                targetState == BrowseScreen.Files && initialState == BrowseScreen.Folders ->
                                    slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(220)
                                    ) + fadeIn(animationSpec = tween(120)) togetherWith
                                        slideOutHorizontally(
                                            targetOffsetX = { -it },
                                            animationSpec = tween(220)
                                        ) + fadeOut(animationSpec = tween(120))
                                targetState == BrowseScreen.Folders && initialState == BrowseScreen.Files ->
                                    slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(220)
                                    ) + fadeIn(animationSpec = tween(120)) togetherWith
                                        slideOutHorizontally(
                                            targetOffsetX = { it },
                                            animationSpec = tween(220)
                                        ) + fadeOut(animationSpec = tween(120))
                                else ->
                                    fadeIn(animationSpec = tween(120)) togetherWith
                                        fadeOut(animationSpec = tween(120))
                            }
                        },
                        label = "BrowseContent"
                    ) { state ->
                        when (state) {
                            BrowseScreen.Folders -> FolderGrid(
                                items = folderItems,
                                onFolderClick = { folder ->
                                    selectedFolderKey = folder.key
                                    selectedFolderName = folder.name
                                    selectedFolderCoverUri = folder.coverUri
                                }
                            )
                            BrowseScreen.Files -> MidiFileList(
                                items = filtered,
                                selectedUri = selectedMidiFileUri,
                                onItemClick = { handleMidiTap(it) }
                            )
                            BrowseScreen.Search -> MidiFileList(
                                items = filtered,
                                selectedUri = selectedMidiFileUri,
                                onItemClick = { handleMidiTap(it) }
                            )
                        }
                    }
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
                color = contentColor,
                modifier = Modifier.clipToBounds()
                    .basicMarquee(Int.MAX_VALUE)
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, folder ->
            FolderCardAnimated(
                folder = folder,
                index = index,
                onClick = { onFolderClick(folder) }
            )
        }
    }
}

@Composable
private fun FolderCardAnimated(
    folder: FolderItem,
    index: Int,
    onClick: () -> Unit
) {
    var visible by remember(folder.key) { mutableStateOf(false) }
    LaunchedEffect(folder.key) {
        delay(0)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 8 },
            animationSpec = tween(0)
        ) + fadeIn(animationSpec = tween(200))
    ) {
        FolderCard(folder = folder, onClick = onClick)
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
                modifier = Modifier.padding(12.dp)
                    .clipToBounds()
                    .basicMarquee(Int.MAX_VALUE),
                maxLines = 1
            )
        }
    }
}

private enum class BrowseScreen {
    Folders,
    Files,
    Search
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
    val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"

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
