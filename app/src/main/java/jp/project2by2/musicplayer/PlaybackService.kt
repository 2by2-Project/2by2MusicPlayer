package jp.project2by2.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.OpenableColumns
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.read
import java.io.File

class PlaybackService : Service() {
    private val binder = LocalBinder()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private var handles: MidiHandles? = null
    private var loopPoint: LoopPoint? = null
    private var syncProc: BASS.SYNCPROC? = null
    private var syncHandle: Int = 0
    private var isForeground = false

    // Media session
    private lateinit var mediaSession: MediaSessionCompat

    // Current playing
    private var currentUriString: String? = null
    private var currentTitle: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bassInit()

        mediaSession = MediaSessionCompat(this, "2by2Playback").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onStop() { stop() }
                override fun onSeekTo(pos: Long) { setCurrentPositionMs(pos) }
            })
            isActive = true
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession.release()
        releaseHandles()
        bassTerminate()
        super.onDestroy()
    }

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

        loopPoint = findLoopPoint(uri)
        val bytes = BASS.BASS_ChannelGetLength(handles!!.stream, BASS.BASS_POS_BYTE)

        BASS.BASS_ChannelFlags(handles!!.stream, BASS.BASS_SAMPLE_LOOP, BASS.BASS_SAMPLE_LOOP)
        BASS.BASS_ChannelFlags(handles!!.stream, BASSMIDI.BASS_MIDI_DECAYSEEK, BASSMIDI.BASS_MIDI_DECAYSEEK)
        BASS.BASS_ChannelFlags(handles!!.stream, BASSMIDI.BASS_MIDI_DECAYEND, BASSMIDI.BASS_MIDI_DECAYEND)

        syncProc = null
        if (syncHandle != 0) {
            BASS.BASS_ChannelRemoveSync(handles!!.stream, syncHandle)
            syncHandle = 0
        }
        loopPoint?.let { lp ->
            syncProc = BASS.SYNCPROC { _, _, _, _ ->
                BASS.BASS_ChannelSetPosition(
                    handles!!.stream,
                    lp.startTick.toLong(),
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
        }

        val title = resolveDisplayName(uri)
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDurationMs())
                .build()
        )

        updateSessionState()
        updateNotification(false)

        // Current playing
        currentUriString = uriString
        currentTitle = title

        return true
    }

    fun play() {
        handles?.let {
            BASS.BASS_ChannelPlay(it.stream, false)
            updateSessionState()
            startForegroundIfNeeded()
            updateNotification(forceShow = true)
        }
        handler.removeCallbacks(stateTicker)
        handler.post(stateTicker)
    }

    fun pause() {
        handles?.let {
            BASS.BASS_ChannelPause(it.stream)
            updateSessionState()
        }
        updateNotification(forceShow = true)
        handler.removeCallbacks(stateTicker)
    }

    fun stop() {
        handles?.let {
            BASS.BASS_ChannelStop(it.stream)
            BASS.BASS_ChannelSetPosition(it.stream, 0, BASS.BASS_POS_BYTE)
        }
        updateSessionState()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        updateNotification(forceShow = false)
        handler.removeCallbacks(stateTicker)
    }

    fun getCurrentPositionMs(): Long {
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
        updateNotification(true)
        updateSessionState()
    }

    fun getDurationMs(): Long {
        val h = handles ?: return 0L
        val bytes = BASS.BASS_ChannelGetLength(h.stream, BASS.BASS_POS_BYTE)
        val secs = BASS.BASS_ChannelBytes2Seconds(h.stream, bytes)
        return (secs * 1000.0).toLong()
    }

    fun getLoopPoint(): LoopPoint? = loopPoint

    fun isPlaying(): Boolean {
        val h = handles ?: return false
        return BASS.BASS_ChannelIsActive(h.stream) == BASS.BASS_ACTIVE_PLAYING
    }

    private fun updateNotification(forceShow: Boolean) {
        val n = buildNotification()
        if (isForeground || forceShow) {
            notificationManager.notify(NOTIFICATION_ID, n)
        }
    }

    private fun startForegroundIfNeeded() {
        if (!isForeground) {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            isForeground = true
        }
    }

    private fun buildNotification(): Notification {
        val isPlaying = isPlaying()

        val playPauseAction = if (isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, playPauseAction)
        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("2by2 Music Player")
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setContentIntent(contentIntent)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_launcher_foreground,
                    if (isPlaying) "Pause" else "Play",
                    playPauseIntent
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_launcher_foreground,
                    "Stop",
                    stopIntent
                )
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateSessionState() {
        val playing = isPlaying()
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    getCurrentPositionMs(),
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    private fun releaseHandles() {
        handles?.let {
            BASS.BASS_StreamFree(it.stream)
            BASSMIDI.BASS_MIDI_FontFree(it.font)
        }
        handles = null
    }

    private fun findLoopPoint(uri: android.net.Uri): LoopPoint {
        val loopPoint = LoopPoint()
        contentResolver.openInputStream(uri)?.use { inputStream ->
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

    private val stateTicker = object : Runnable {
        override fun run() {
            if (isPlaying()) {
                updateSessionState()
                handler.postDelayed(this, 200)
            }
        }
    }

    fun getCurrentUriString(): String? = currentUriString
    fun getCurrentTitle(): String? = currentTitle

    private fun resolveDisplayName(uri: android.net.Uri): String {
        // SAF / OpenDocument のURIなら基本ここで取れる
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
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

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val MIDI_FILE = "midi.mid"
        private const val SOUND_FONT_FILE = "soundfont.sf2"
        private const val ACTION_PLAY = "jp.project2by2.musicplayer.action.PLAY"
        private const val ACTION_PAUSE = "jp.project2by2.musicplayer.action.PAUSE"
        private const val ACTION_STOP = "jp.project2by2.musicplayer.action.STOP"
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
