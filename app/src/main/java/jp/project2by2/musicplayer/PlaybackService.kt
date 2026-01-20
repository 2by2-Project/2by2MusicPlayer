package jp.project2by2.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.read
import java.io.File

class PlaybackService : Service() {
    private val binder = LocalBinder()
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private var handles: MidiHandles? = null
    private var loopPoint: LoopPoint? = null
    private var syncProc: BASS.SYNCPROC? = null
    private var syncHandle: Int = 0
    private var isForeground = false

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bassInit()
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

        updateNotification()
        return true
    }

    fun play() {
        handles?.let {
            BASS.BASS_ChannelPlay(it.stream, false)
            startForegroundIfNeeded()
            updateNotification()
        }
    }

    fun pause() {
        handles?.let {
            BASS.BASS_ChannelPause(it.stream)
            updateNotification()
        }
    }

    fun stop() {
        handles?.let {
            BASS.BASS_ChannelStop(it.stream)
            BASS.BASS_ChannelSetPosition(it.stream, 0, BASS.BASS_POS_BYTE)
            updateNotification()
        }
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
    }

    fun getCurrentPositionMs(): Long {
        val h = handles ?: return 0L
        val bytes = BASS.BASS_ChannelGetPosition(h.stream, BASS.BASS_POS_BYTE)
        val secs = BASS.BASS_ChannelBytes2Seconds(h.stream, bytes)
        return (secs * 1000.0).toLong()
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

    private fun updateNotification() {
        val notification = buildNotification()
        if (isForeground) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundIfNeeded() {
        if (!isForeground) {
            startForeground(NOTIFICATION_ID, buildNotification())
            isForeground = true
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val playIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY),
            PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = isPlaying()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("2by2 Music Player")
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .addAction(
                R.drawable.ic_launcher_foreground,
                if (isPlaying) "Pause" else "Play",
                if (isPlaying) pauseIntent else playIntent
            )
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopIntent)
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
