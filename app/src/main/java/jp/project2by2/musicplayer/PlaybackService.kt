package jp.project2by2.musicplayer

import android.R.attr.defaultValue
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
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

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val binder = LocalBinder()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var handles: MidiHandles? = null
    private var loopPoint: LoopPoint? = null
    private var syncProc: BASS.SYNCPROC? = null
    private var syncHandle: Int = 0

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

        // Current playing
        currentUriString = uriString
        currentTitle = resolveDisplayName(uri)

        // Media3
        bassPlayer.setMetadata(currentTitle!!, currentArtist, currentArtworkUri)
        bassPlayer.invalidateFromBass()

        loopPoint = findLoopPoint(cacheMidiFile)
        val bytes = BASS.BASS_ChannelGetLength(h.stream, BASS.BASS_POS_BYTE)

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
                BASS.BASS_ChannelSetPosition(
                    h.stream,
                    lp.startTick.toLong(),
                    BASSMIDI.BASS_POS_MIDI_TICK or BASSMIDI.BASS_MIDI_DECAYSEEK
                )

                notifyLooped(lp.startMs)
            }
            syncHandle = BASS.BASS_ChannelSetSync(
                h.stream,
                BASS.BASS_SYNC_MIXTIME,
                bytes,
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

    fun getEffectDisabled(): Boolean {
        val h = handles ?: return false
        val flags = BASS.BASS_ChannelFlags(h.stream, 0, 0)
        if (flags == -1L) {
            return false
        }
        return (flags.toInt() and BASSMIDI.BASS_MIDI_NOFX) != 0
    }

    fun getReverbStrength(): Float {
        val h = handles ?: return defaultValue.toFloat()
        val out = BASS.FloatValue()   // ← BASS.java にある想定
        val ok = BASS.BASS_ChannelGetAttribute(
            h.stream,
            BASSMIDI.BASS_ATTRIB_MIDI_REVERB,
            out
        )
        return if (ok) out.value else defaultValue.toFloat()
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
            BASS.BASS_StreamFree(it.stream)
            BASSMIDI.BASS_MIDI_FontFree(it.font)
        }
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
)

data class MidiHandles(
    val stream: Int,
    val font: Int
)
