package jp.project2by2.musicplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : ComponentActivity() {
    private var boundService by mutableStateOf<PlaybackService?>(null)
    private var isBound = false

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as? PlaybackService.LocalBinder
            boundService = binder?.getService()
            isBound = (boundService != null)
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            isBound = false
            boundService = null
        }
    }

    override fun onStart() {
        super.onStart()
        // サービスが起動していない可能性があるなら startService → bind の順にすると堅い
        startService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        boundService = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            _2by2MusicPlayerTheme {
                SettingsScreen(playbackService = boundService)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(playbackService: PlaybackService?) {
    val context = LocalContext.current
    val activity = LocalActivity.current as Activity
    val scope = rememberCoroutineScope()
    val soundFontName by remember(context) {
        SettingsDataStore.soundFontNameFlow(context)
    }.collectAsState(initial = null)
    var hasSoundFont by remember { mutableStateOf(File(context.cacheDir, "soundfont.sf2").exists()) }

    val svc = playbackService

    var effectsEnabled by remember { mutableStateOf(false) }
    var reverbStrength by remember { mutableStateOf(1f) }

    val loopEnabled by SettingsDataStore.loopEnabledFlow(context).collectAsState(initial = false)
    val loopMode by SettingsDataStore.loopModeFlow(context).collectAsState(initial = 0)
    val shuffleEnabled by SettingsDataStore.shuffleEnabledFlow(context).collectAsState(initial = false)

    var showSoundFontDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(svc) {
        // Load settings
        effectsEnabled = SettingsDataStore.effectsEnabledFlow(context).first()
        reverbStrength = SettingsDataStore.reverbStrengthFlow(context).first()
    }

    fun resolveDisplayName(uri: Uri): String {
        // SAF / OpenDocument のURIなら基本ここで取れる
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }

        // fallback
        return uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.unknown)
    }

    val soundFontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val cacheSoundFontFile = File(context.cacheDir, "soundfont.sf2")
            context.contentResolver.openInputStream(it)?.use { input ->
                cacheSoundFontFile.outputStream().use { output -> input.copyTo(output) }
            }
            hasSoundFont = cacheSoundFontFile.exists()
            val name = resolveDisplayName(it)
            scope.launch {
                SettingsDataStore.setSoundFontName(context, name)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = stringResource(id = R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            LazyColumn(
                modifier = Modifier.padding(8.dp)
            ) {
                // MIDI Synthesizer
                item {
                    Text(stringResource(id = R.string.settings_category_midi_synthesizer), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                }
                item {
                    val label = soundFontName ?: stringResource(id = R.string.settings_soundfont_loaded)
                    SettingsInfoItem(title = stringResource(id = R.string.settings_soundfont_title), value = if (hasSoundFont) label else stringResource(id = R.string.settings_soundfont_not_set))
                    Button(
                        onClick = { soundFontPicker.launch("application/octet-stream") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text(stringResource(id = R.string.settings_soundfont_load_button))
                    }
                    // Recommended soundfonts
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        onClick = { showSoundFontDialog = true },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(stringResource(id = R.string.settings_soundfont_recommended_button))
                    }
                }
                item {
                    val maxVoices by SettingsDataStore.maxVoicesFlow(context).collectAsState(initial = 40)
                    SettingsDropdownItem(
                        title = stringResource(id = R.string.settings_max_voices_title),
                        options = listOf("20", "40", "100", "200"),
                        defaultValue = maxVoices.toString(),
                        onSelectedChange = {
                            scope.launch {
                                val maxVoices = it.toIntOrNull() ?: 40
                                SettingsDataStore.setMaxVoices(context, maxVoices)
                                svc?.setMaxVoices(maxVoices)
                            }
                        }
                    )
                }
                item {
                    SettingsSwitchItem(
                        title = stringResource(id = R.string.settings_effects_toggle_title),
                        checked = effectsEnabled,
                        onCheckedChange = { checked ->
                            effectsEnabled = checked
                            svc?.setEffectDisabled(!checked)
                            scope.launch {
                                SettingsDataStore.setEffectsEnabled(context, checked)
                            }
                        }
                    )
                }
                item {
                    AnimatedVisibility(
                        visible = effectsEnabled,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp)
                            ) {
                                SettingsSliderItem(
                                    title = stringResource(id = R.string.settings_effects_reverb_strength),
                                    value = reverbStrength,
                                    enabled = effectsEnabled,
                                    valueRange = 0.0f..3.0f,
                                    onValueChange = { v ->
                                        reverbStrength = v
                                        svc?.setReverbStrength(v)
                                        scope.launch {
                                            SettingsDataStore.setReverbStrength(context, v)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                // Playback
                item {
                    Text(stringResource(id = R.string.settings_category_playback), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                }
                item {
                    SettingsSwitchItem(
                        title = stringResource(id = R.string.settings_playback_loop_toggle),
                        checked = loopEnabled,
                        onCheckedChange = { checked ->
                            scope.launch {
                                SettingsDataStore.setLoopEnabled(context, checked)
                            }
                        }
                    )
                }
                item {
                    AnimatedVisibility(
                        visible = loopEnabled,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp)
                            ) {
                                SettingsRadioItem(
                                    text = stringResource(id = R.string.settings_playback_loop_play_indefinitely),
                                    enabled = loopEnabled,
                                    selected = loopMode == 0,
                                    onClick = {
                                        scope.launch {
                                            SettingsDataStore.setLoopMode(context, 0)
                                        }
                                    }
                                )
                                SettingsRadioItem(
                                    text = stringResource(id = R.string.settings_playback_loop_play_indefinitely_when_detected),
                                    enabled = loopEnabled,
                                    selected = loopMode == 1,
                                    onClick = {
                                        scope.launch {
                                            SettingsDataStore.setLoopMode(context, 1)
                                        }
                                    }
                                )
                                SettingsRadioItem(
                                    text = stringResource(id = R.string.settings_playback_loop_loop_and_fade),
                                    enabled = loopEnabled,
                                    selected = loopMode == 2,
                                    onClick = {
                                        scope.launch {
                                            SettingsDataStore.setLoopMode(context, 2)
                                        }
                                    }
                                )
                                SettingsRadioItem(
                                    text = stringResource(id = R.string.settings_playback_loop_loop_and_fade_when_detected),
                                    enabled = loopEnabled,
                                    selected = loopMode == 3,
                                    onClick = {
                                        scope.launch {
                                            SettingsDataStore.setLoopMode(context, 3)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsSwitchItem(
                        title = stringResource(id = R.string.settings_playback_shuffle_toggle),
                        checked = shuffleEnabled,
                        onCheckedChange = { checked ->
                            scope.launch {
                                SettingsDataStore.setShuffleEnabled(context, checked)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSoundFontDialog) {
        SoundFontDownloadDialog(
            onDismiss = { showSoundFontDialog = false },
            onDownloadComplete = {
                hasSoundFont = File(context.cacheDir, "soundfont.sf2").exists()
            }
        )
    }
}

@Composable
fun SettingsInfoItem(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingsSliderItem(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true
) {
    var uiValue by remember { mutableFloatStateOf(value) }

    LaunchedEffect(value) {
        uiValue = value
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        Slider(
            value = uiValue,
            onValueChange = { v ->
                uiValue = v
            },
            onValueChangeFinished = {
                onValueChange(uiValue)
            },
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}


@Composable
private fun SettingsRadioItem(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = { if (enabled) onClick() else null },
                role = androidx.compose.ui.semantics.Role.RadioButton
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = null
        )
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdownItem(
    title: String,
    options: List<String>,
    defaultValue: String,
    onSelectedChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(defaultValue) { mutableStateOf(defaultValue) }

    LaunchedEffect(defaultValue) {
        if (defaultValue in options) {
            selected = defaultValue
        } else {
            selected = options.first()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            TextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .widthIn(min = 80.dp, max = 180.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            selected = option
                            onSelectedChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}