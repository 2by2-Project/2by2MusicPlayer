package jp.project2by2.musicplayer

import android.app.PendingIntent
import android.content.ContentUris
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.read
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlin.random.Random

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val binder = LocalBinder()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = Random(System.currentTimeMillis())

    private var handles: MidiHandles? = null
    private var loopPoint: LoopPoint? = null
    private var syncProc: BASS.SYNCPROC? = null
    private var syncHandle: Int = 0
    private var volumeSlideSyncHandle: Int = 0
    private var loopRepeatCount: Int = 0
    private val transitionInProgress = AtomicBoolean(false)
    @Volatile private var loopEnabledSnapshot: Boolean = false
    @Volatile private var loopModeSnapshot: Int = 0
    @Volatile private var shuffleEnabledSnapshot: Boolean = false

    // Media session
    private lateinit var bassPlayer: BassPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationProvider: DefaultMediaNotificationProvider

    // Current playing
    private var currentUriString: String? = null
    private var currentTitle: String? = null
    public var currentArtist: String? = null
    public var currentArtworkUri: Uri? = null

    @Volatile private var loopPositionOverrideMs: Long? = null
    @Volatile private var loopOverrideUntilUptimeMs: Long = 0L

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        bassInit()
        observePlaybackSettings()

        bassPlayer = BassPlayer(
            looper = Looper.getMainLooper(),
            onPlay = { playInternalFromController() },
            onPause = { pauseInternalFromController(releaseFocus = true) },
            onSeek = { ms -> seekInternalFromController(ms) },
            queryPositionMs = { getCurrentPositionMs() },
            queryDurationMs = { getDurationMs() },
            queryIsPlaying = { isPlaying() },
        )
        mediaSession = MediaSession.Builder(this, bassPlayer).build()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, bassPlayer)
            .setId("2by2Playback")
            .setSessionActivity(contentIntent)
            .build()

        notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.app_name)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build().also { provider ->
                provider.setSmallIcon(R.drawable.notification_icon)
            }
        setMediaNotificationProvider(notificationProvider)
    }

    private fun observePlaybackSettings() {
        serviceScope.launch {
            SettingsDataStore.loopEnabledFlow(this@PlaybackService).collectLatest { loopEnabledSnapshot = it }
        }
        serviceScope.launch {
            SettingsDataStore.loopModeFlow(this@PlaybackService).collectLatest { loopModeSnapshot = it.coerceIn(0, 3) }
        }
        serviceScope.launch {
            SettingsDataStore.shuffleEnabledFlow(this@PlaybackService).collectLatest { shuffleEnabledSnapshot = it }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == MediaSessionService.SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onDestroy() {
        unregisterNoisyReceiver()
        abandonAudioFocus()
        mediaSession.release()
        serviceScope.cancel()
        releaseHandles()
        bassTerminate()
        bassPlayer.release()
        super.onDestroy()
    }

    private fun isPlayingBass(): Boolean {
        val h = handles ?: return false
        return BASS.BASS_ChannelIsActive(h.stream) == BASS.BASS_ACTIVE_PLAYING
    }

    private fun setBassPositionMs(ms: Long) {
        val h = handles ?: return
        val secs = ms.coerceAtLeast(0L).toDouble() / 1000.0
        val bytes = BASS.BASS_ChannelSeconds2Bytes(h.stream, secs)
        BASS.BASS_ChannelSetPosition(h.stream, bytes, BASS.BASS_POS_BYTE)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun loadMidi(uriString: String): Boolean {
        val uri = android.net.Uri.parse(uriString)
        loopRepeatCount = 0

        val cacheSoundFontFile = File(cacheDir, SOUND_FONT_FILE)
        if (!cacheSoundFontFile.exists()) {
            return false
        }

        val cacheMidiFile = File(cacheDir, MIDI_FILE)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                cacheMidiFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
        } catch (e: Exception) {
            return false
        }

        releaseHandles()
        handles = bassLoadMidiWithSoundFont(cacheMidiFile.absolutePath, cacheSoundFontFile.absolutePath)
        val h = handles ?: return false
        if (h.stream == 0 || h.font == 0) {
            releaseHandles()
            return false
        }

        // Apply effect stuff
        val (enabled, reverb) = runBlocking {
            val enabled = SettingsDataStore.effectsEnabledFlow(this@PlaybackService).first()
            val reverb = SettingsDataStore.reverbStrengthFlow(this@PlaybackService).first()
            enabled to reverb
        }

        setEffectDisabled(!enabled)
        setReverbStrength(reverb)

        // Current playing
        currentUriString = uriString
        currentTitle = resolveDisplayName(uri)

        // Media3
        bassPlayer.setMetadata(currentTitle!!, currentArtist, currentArtworkUri)
        bassPlayer.invalidateFromBass()

        loopPoint = findLoopPoint(cacheMidiFile)
        val bytes = BASS.BASS_ChannelGetLength(h.stream, BASS.BASS_POS_BYTE)

        BASS.BASS_ChannelSetAttribute(h.stream, BASS.BASS_ATTRIB_VOL, 1f)
        BASS.BASS_ChannelFlags(h.stream, BASS.BASS_SAMPLE_LOOP, BASS.BASS_SAMPLE_LOOP)
        BASS.BASS_ChannelFlags(h.stream, BASSMIDI.BASS_MIDI_DECAYSEEK, BASSMIDI.BASS_MIDI_DECAYSEEK)
        BASS.BASS_ChannelFlags(h.stream, BASSMIDI.BASS_MIDI_DECAYEND, BASSMIDI.BASS_MIDI_DECAYEND)

        syncProc = null
        if (syncHandle != 0) {
            BASS.BASS_ChannelRemoveSync(h.stream, syncHandle)
            syncHandle = 0
        }
        loopPoint?.let { lp ->
            syncProc = BASS.SYNCPROC { _, _, _, _ ->
                handlePlaybackBoundary(lp, h.stream)
            }
            val syncType: Int
            val syncParam: Long
            if (lp.startTick > 0 && loopEnabledSnapshot) {
                syncType = BASS.BASS_SYNC_MIXTIME
                syncParam = bytes
            } else {
                syncType = BASS.BASS_SYNC_END
                syncParam = 0L
            }
            syncHandle = BASS.BASS_ChannelSetSync(
                h.stream,
                syncType,
                syncParam,
                syncProc,
                0
            )
        }

        return true
    }

    fun play() {
        if (!requestAudioFocus()) {
            return
        }
        registerNoisyReceiver()

        handles?.let { BASS.BASS_ChannelPlay(it.stream, false) }
        bassPlayer.invalidateFromBass()
    }

    fun pauseInternal(releaseFocus: Boolean) {
        unregisterNoisyReceiver()
        if (releaseFocus) {
            abandonAudioFocus()
        }

        handles?.let { BASS.BASS_ChannelPause(it.stream) }
        bassPlayer.invalidateFromBass()
    }
    fun pause() = pauseInternal(releaseFocus = true)

    fun stop() {
        unregisterNoisyReceiver()
        abandonAudioFocus()

        handles?.let {
            BASS.BASS_ChannelPause(it.stream)
            BASS.BASS_ChannelSetPosition(it.stream, 0, BASS.BASS_POS_BYTE)
        }
        bassPlayer.invalidateFromBass()
    }

    fun getCurrentPositionMs(): Long {
        val now = SystemClock.uptimeMillis()
        val override = loopPositionOverrideMs
        if (override != null && now < loopOverrideUntilUptimeMs) {
            return override
        }

        val h = handles ?: return 0L
        val bytes = BASS.BASS_ChannelGetPosition(h.stream, BASS.BASS_POS_BYTE)
        val secs = BASS.BASS_ChannelBytes2Seconds(h.stream, bytes)
        return (secs * 1000.0).toLong()
    }

    fun setCurrentPositionMs(ms: Long) {
        val h = handles ?: return
        val secs = ms.coerceAtLeast(0L).toDouble() / 1000.0
        val bytes = BASS.BASS_ChannelSeconds2Bytes(h.stream, secs)
        BASS.BASS_ChannelSetPosition(h.stream, bytes, BASS.BASS_POS_BYTE)
        bassPlayer.invalidateFromBass()
    }

    fun getDurationMs(): Long {
        val h = handles ?: return 0L
        val bytes = BASS.BASS_ChannelGetLength(h.stream, BASS.BASS_POS_BYTE)
        val secs = BASS.BASS_ChannelBytes2Seconds(h.stream, bytes)
        return (secs * 1000.0).toLong()
    }

    fun setEffectDisabled(value: Boolean) {
        val h = handles ?: return
        val flagsToSet = if (value) BASSMIDI.BASS_MIDI_NOFX else 0
        BASS.BASS_ChannelFlags(
            h.stream,
            flagsToSet,
            BASSMIDI.BASS_MIDI_NOFX
        )
    }

    fun setReverbStrength(value: Float) {
        handles?.let {
            BASS.BASS_ChannelSetAttribute(
                it.stream,
                BASSMIDI.BASS_ATTRIB_MIDI_REVERB,
                value
            )
        }
    }

    fun getLoopPoint(): LoopPoint? = loopPoint

    fun isPlaying(): Boolean {
        val h = handles ?: return false
        return BASS.BASS_ChannelIsActive(h.stream) == BASS.BASS_ACTIVE_PLAYING
    }

    private fun releaseHandles() {
        handles?.let {
            if (syncHandle != 0) {
                BASS.BASS_ChannelRemoveSync(it.stream, syncHandle)
                syncHandle = 0
            }
            if (volumeSlideSyncHandle != 0) {
                BASS.BASS_ChannelRemoveSync(it.stream, volumeSlideSyncHandle)
                volumeSlideSyncHandle = 0
            }
            BASS.BASS_StreamFree(it.stream)
            BASSMIDI.BASS_MIDI_FontFree(it.font)
        }
        loopRepeatCount = 0
        transitionInProgress.set(false)
        handles = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun findLoopPoint(midiFile: File): LoopPoint {
        val loopPoint = LoopPoint()
        try {
            midiFile.inputStream().use { inputStream ->
                val bytes = inputStream.readAllBytes().toList()
                val music = Midi1Music().apply { read(bytes) }

                for (track in music.tracks) {
                    var tick = 0
                    for (e in track.events) {
                        tick += e.deltaTime
                        val m = e.message
                        val isCC = ((m.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC)
                        if (isCC && m.msb.toInt() == 111) {
                            loopPoint.hasLoopStartMarker = true
                            loopPoint.startTick = tick
                            loopPoint.startMs = music.getTimePositionInMillisecondsForTick(tick).toLong()
                        }
                    }
                }

                for (track in music.tracks) {
                    var tick = 0
                    for (e in track.events) tick += e.deltaTime
                    loopPoint.endTick = tick
                    loopPoint.endMs = music.getTimePositionInMillisecondsForTick(tick).toLong()
                }
            }
        } catch (_: Exception) {
            // Parse errors should not crash playback. Use default loop values.
        }
        return loopPoint
    }

    private fun handlePlaybackBoundary(lp: LoopPoint, streamHandle: Int) {
        if (handles?.stream != streamHandle) return

        val loopEnabled = loopEnabledSnapshot
        val loopMode = loopModeSnapshot
        val shuffleEnabled = shuffleEnabledSnapshot
        if (!loopEnabled) {
            loopRepeatCount = 0
            serviceScope.launch { playNextTrackInCurrentFolder(shuffleEnabled) }
            return
        }

        val requireLoopMarker = (loopMode == 1 || loopMode == 3)
        if (requireLoopMarker && !lp.hasLoopStartMarker) {
            loopRepeatCount = 0
            serviceScope.launch { playNextTrackInCurrentFolder(shuffleEnabled) }
            return
        }

        if (loopMode == 2 || loopMode == 3) {
            if (loopRepeatCount < LOOP_REPEAT_BEFORE_FADE_COUNT) {
                loopRepeatCount += 1
                seekToLoopStart(streamHandle, lp)
                return
            }
            loopRepeatCount = 0
            fadeOutFromLoopStartThenPlayNext(streamHandle, lp, shuffleEnabled)
            return
        }

        seekToLoopStart(streamHandle, lp)
    }

    private fun seekToLoopStart(streamHandle: Int, lp: LoopPoint) {
        BASS.BASS_ChannelSetPosition(
            streamHandle,
            lp.startTick.toLong(),
            BASSMIDI.BASS_POS_MIDI_TICK or BASSMIDI.BASS_MIDI_DECAYSEEK
        )
        notifyLooped(lp.startMs)
    }

    private fun fadeOutFromLoopStartThenPlayNext(streamHandle: Int, lp: LoopPoint, shuffleEnabled: Boolean) {
        if (!transitionInProgress.compareAndSet(false, true)) return
        if (handles?.stream != streamHandle) {
            transitionInProgress.set(false)
            return
        }

        seekToLoopStart(streamHandle, lp)
        val fadeDuration = FADE_OUT_DURATION_MS
        fadeOutThenPlayNext(streamHandle, shuffleEnabled, fadeDuration, alreadyLocked = true)
    }

    private fun fadeOutThenPlayNext(
        streamHandle: Int,
        shuffleEnabled: Boolean,
        fadeDurationMs: Int = FADE_OUT_DURATION_MS,
        alreadyLocked: Boolean = false
    ) {
        if (!alreadyLocked && !transitionInProgress.compareAndSet(false, true)) return
        if (handles?.stream != streamHandle) {
            transitionInProgress.set(false)
            return
        }

        if (volumeSlideSyncHandle != 0) {
            BASS.BASS_ChannelRemoveSync(streamHandle, volumeSlideSyncHandle)
            volumeSlideSyncHandle = 0
        }

        val sliding = BASS.BASS_ChannelSlideAttribute(
            streamHandle,
            BASS.BASS_ATTRIB_VOL,
            0f,
            fadeDurationMs
        )
        if (!sliding) {
            transitionInProgress.set(false)
            serviceScope.launch { playNextTrackInCurrentFolder(shuffleEnabled, alreadyLocked = false) }
            return
        }

        volumeSlideSyncHandle = BASS.BASS_ChannelSetSync(
            streamHandle,
            BASS.BASS_SYNC_SLIDE,
            BASS.BASS_ATTRIB_VOL.toLong(),
            BASS.SYNCPROC { _, _, _, _ ->
                serviceScope.launch {
                    playNextTrackInCurrentFolder(shuffleEnabled, alreadyLocked = true)
                }
            },
            0
        )
    }

    suspend fun playNextTrackInCurrentFolder(shuffleEnabled: Boolean, alreadyLocked: Boolean = false) {
        if (!alreadyLocked && !transitionInProgress.compareAndSet(false, true)) return

        val currentUri = currentUriString?.let { Uri.parse(it) }
        if (currentUri == null) {
            transitionInProgress.set(false)
            return
        }

        val nextUri = findNextUriInCurrentFolder(currentUri, shuffleEnabled)
        if (nextUri == null) {
            transitionInProgress.set(false)
            return
        }

        val loaded = loadMidi(nextUri.toString())
        if (loaded) {
            mainHandler.post {
                play()
                bassPlayer.invalidateFromBass()
                triggerNotificationUpdate()
            }
        }
        transitionInProgress.set(false)
    }

    suspend fun playPreviousTrackInCurrentFolder(shuffleEnabled: Boolean, alreadyLocked: Boolean = false) {
        if (!alreadyLocked && !transitionInProgress.compareAndSet(false, true)) return

        val currentUri = currentUriString?.let { Uri.parse(it) }
        if (currentUri == null) {
            transitionInProgress.set(false)
            return
        }

        val previousUri = findPreviousUriInCurrentFolder(currentUri, shuffleEnabled)
        if (previousUri == null) {
            transitionInProgress.set(false)
            return
        }

        val loaded = loadMidi(previousUri.toString())
        if (loaded) {
            mainHandler.post {
                play()
                bassPlayer.invalidateFromBass()
                triggerNotificationUpdate()
            }
        }
        transitionInProgress.set(false)
    }

    private fun findNextUriInCurrentFolder(currentUri: Uri, shuffleEnabled: Boolean): Uri? {
        val playlist = queryFolderPlaylist(currentUri)
        if (playlist.isEmpty()) return null

        val currentIndex = playlist.indexOfFirst { it.toString() == currentUri.toString() }
        if (currentIndex < 0) return playlist.first()

        if (shuffleEnabled) {
            if (playlist.size <= 1) return playlist.first()
            var candidate = currentIndex
            while (candidate == currentIndex) {
                candidate = random.nextInt(playlist.size)
            }
            return playlist[candidate]
        }

        return playlist[(currentIndex + 1) % playlist.size]
    }

    private fun findPreviousUriInCurrentFolder(currentUri: Uri, shuffleEnabled: Boolean): Uri? {
        val playlist = queryFolderPlaylist(currentUri)
        if (playlist.isEmpty()) return null

        val currentIndex = playlist.indexOfFirst { it.toString() == currentUri.toString() }
        if (currentIndex < 0) return playlist.last()

        if (shuffleEnabled) {
            if (playlist.size <= 1) return playlist.first()
            var candidate = currentIndex
            while (candidate == currentIndex) {
                candidate = random.nextInt(playlist.size)
            }
            return playlist[candidate]
        }

        val previousIndex = if (currentIndex == 0) playlist.lastIndex else currentIndex - 1
        return playlist[previousIndex]
    }

    private fun queryFolderPlaylist(currentUri: Uri): List<Uri> {
        val folderKey = resolveFolderKey(currentUri) ?: return emptyList()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = (
            "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            )
        val selectionArgs = arrayOf("audio/midi", "audio/x-midi", "%.mid", "%.midi")
        val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"

        val rows = mutableListOf<Pair<String, Uri>>()
        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val dataColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)

            while (cursor.moveToNext()) {
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null
                val dataPath = if (dataColumn >= 0) cursor.getString(dataColumn) else null
                val itemFolderKey = extractFolderKey(relativePath, dataPath)
                if (itemFolderKey != folderKey) continue

                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: ""
                val uri = ContentUris.withAppendedId(collection, id)
                rows.add(name.lowercase() to uri)
            }
        }

        return rows.sortedBy { it.first }.map { it.second }
    }

    private fun resolveFolderKey(uri: Uri): String? {
        val projection = arrayOf(
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATA
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val relativePathColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val dataColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null
            val dataPath = if (dataColumn >= 0) cursor.getString(dataColumn) else null
            val folderKey = extractFolderKey(relativePath, dataPath)
            return folderKey.ifBlank { null }
        }
        return null
    }

    private fun extractFolderKey(relativePath: String?, dataPath: String?): String {
        relativePath?.let {
            val trimmed = it.trimEnd('/', '\\')
            if (trimmed.isNotBlank()) return trimmed.replace('\\', '/')
        }
        dataPath?.let {
            val normalized = it.replace('\\', '/')
            val parent = normalized.substringBeforeLast('/', "")
            if (parent.isNotBlank()) return parent
        }
        return ""
    }

    private fun bassInit(): Boolean {
        return BASS.BASS_Init(-1, 44100, 0)
    }

    private fun bassTerminate() {
        BASS.BASS_Free()
    }

    private fun bassLoadMidiWithSoundFont(midiPath: String, sf2Path: String): MidiHandles {
        val soundFontHandle = BASSMIDI.BASS_MIDI_FontInit(sf2Path, 0)
        val stream = BASSMIDI.BASS_MIDI_StreamCreateFile(midiPath, 0, 0, 0, 0)
        val fonts = arrayOf(
            BASSMIDI.BASS_MIDI_FONT().apply {
                font = soundFontHandle
                preset = -1
                bank = 0
            }
        )
        BASSMIDI.BASS_MIDI_StreamSetFonts(stream, fonts, 1)
        return MidiHandles(stream, soundFontHandle)
    }

    fun getCurrentUriString(): String? = currentUriString
    fun getCurrentTitle(): String? = currentTitle

    private fun resolveDisplayName(uri: android.net.Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }

        return uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
    }

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private var hasAudioFocus = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }
    private var noisyReceiverRegistered = false

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val result = if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            noisyReceiverRegistered = false
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pauseInternal(releaseFocus = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = isPlaying()
                pauseInternal(releaseFocus = false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val MIDI_FILE = "midi.mid"
        private const val SOUND_FONT_FILE = "soundfont.sf2"
        private const val LOOP_REPEAT_BEFORE_FADE_COUNT = 1
        private const val FADE_OUT_DURATION_MS = 8000
    }

    private fun playInternalFromController() {
        if (!requestAudioFocus()) {
            bassPlayer.invalidateFromBass()
            return
        }
        registerNoisyReceiver()
        handles?.let { BASS.BASS_ChannelPlay(it.stream, false) }
        bassPlayer.invalidateFromBass()
    }

    private fun pauseInternalFromController(releaseFocus: Boolean) {
        handles?.let { BASS.BASS_ChannelPause(it.stream) }
        unregisterNoisyReceiver()
        if (releaseFocus) abandonAudioFocus()
        bassPlayer.invalidateFromBass()
    }

    private fun seekInternalFromController(ms: Long) {
        setCurrentPositionMs(ms)
        bassPlayer.invalidateFromBass()
    }

    private fun notifyLooped(startMs: Long) {
        loopPositionOverrideMs = startMs
        loopOverrideUntilUptimeMs = SystemClock.uptimeMillis() + 300L

        mainHandler.post {
            bassPlayer.invalidateFromBass()
            triggerNotificationUpdate()
        }
    }
}

data class LoopPoint(
    var startMs: Long = 0L,
    var endMs: Long = -1L,
    var startTick: Int = 0,
    var endTick: Int = -1,
    var hasLoopStartMarker: Boolean = false,
)

data class MidiHandles(
    val stream: Int,
    val font: Int
)
