package jp.project2by2.musicplayer

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.roundToInt

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

    var effectsEnabled by remember { mutableStateOf(true) }
    var reverbStrength by remember { mutableStateOf(1f) }

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
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
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
                    Text("MIDI Synthesizer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                }
                item {
                    val label = soundFontName ?: "Loaded"
                    SettingsInfoItem(title = "SoundFont", value = if (hasSoundFont) label else "Not set")
                    ElevatedButton(
                        onClick = { soundFontPicker.launch("application/octet-stream") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text("Load SoundFont")
                    }
                }
                item {
                    SettingsSwitchItem(
                        title = "Enable Reverb / Chorus effects",
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
                    SettingsSliderItem(
                        title = "Reverb strength",
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