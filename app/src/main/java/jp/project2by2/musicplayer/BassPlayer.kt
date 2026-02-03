package jp.project2by2.musicplayer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class BassPlayer(
    looper: Looper = Looper.getMainLooper(),
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val queryPositionMs: () -> Long,
    private val queryDurationMs: () -> Long,
    private val queryIsPlaying: () -> Boolean,
) : SimpleBasePlayer(looper) {
    private val playerHandler = Handler(looper)
    private val applicationLooper = looper

    private var title: String = "2by2 Music Player"
    private var artist: String? = null
    private var artworkUri: Uri? = null

    private val mediaId = "midi"

    fun setMetadata(title: String, artist: String?, artworkUri: Uri?) {
        val update = {
            this.title = title
            this.artist = artist
            this.artworkUri = artworkUri
            invalidateState()
        }
        if (Looper.myLooper() == applicationLooper) {
            update()
        } else {
            playerHandler.post(update)
        }
    }

    fun invalidateFromBass() {
        val update = {
            invalidateState()
        }
        if (Looper.myLooper() == applicationLooper) {
            update()
        } else {
            playerHandler.post(update)
        }
    }

    override fun getState(): State {
        val isPlaying = queryIsPlaying()
        val posMs = queryPositionMs()
        val durMs = queryDurationMs()
        val durUs = if (durMs > 0) durMs * 1000 else C.TIME_UNSET

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(artworkUri)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()

        val itemData = MediaItemData.Builder(mediaId)
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setDurationUs(durUs)
            .setIsSeekable(durMs > 0)
            .build()

        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(posMs)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) onPlay() else onPause()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        onSeek(positionMs)
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
