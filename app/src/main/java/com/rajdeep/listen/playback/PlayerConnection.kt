package com.rajdeep.listen.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.rajdeep.listen.data.LibraryRepository
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.data.online.OnlineTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class NowPlaying(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    /** Uri for online art, [com.rajdeep.listen.data.LocalArt] for local files. */
    val artwork: Any?,
    val albumId: Long?,
    val isPreview: Boolean,
    val isLive: Boolean = false,
    val source: String,
    val durationHint: Long,
) {
    val localId: Long? get() = MediaKeys.localId(mediaId)
}

@Immutable
data class QueueEntry(val index: Int, val item: NowPlaying)

@Immutable
data class PlayerUiState(
    val current: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = 0,
    val error: String? = null,
    val sleepAt: Long = 0,
)

/** UI-side handle to [PlaybackService]. All UI talks to playback through this. */
class PlayerConnection(private val context: Context, private val library: LibraryRepository, private val scope: CoroutineScope) {

    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var sleepJob: Job? = null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun connect() {
        if (controller != null) return
        scope.launch(Dispatchers.Main) {
            if (controller != null) return@launch
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = runCatching { MediaController.Builder(context, token).buildAsync().await() }.getOrNull() ?: return@launch
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { sync(player) }
            })
            sync(c)
            pending.forEach { it(c) }; pending.clear()
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run { pending += block; connect() }
    }

    private fun MediaItem.toNowPlaying(): NowPlaying {
        val m = mediaMetadata
        val ex = m.extras
        return NowPlaying(
            mediaId = mediaId,
            title = m.title?.toString() ?: "Unknown",
            artist = m.artist?.toString() ?: "",
            album = m.albumTitle?.toString() ?: "",
            artwork = ex?.getString(MediaKeys.EXTRA_URI)?.let { com.rajdeep.listen.data.LocalArt(it) } ?: m.artworkUri,
            albumId = ex?.getLong(MediaKeys.EXTRA_ALBUM_ID, -1)?.takeIf { it >= 0 },
            isPreview = ex?.getBoolean(MediaKeys.EXTRA_PREVIEW) ?: false,
            isLive = ex?.getBoolean(MediaKeys.EXTRA_LIVE) ?: false,
            source = ex?.getString(MediaKeys.EXTRA_SOURCE) ?: "Local",
            durationHint = ex?.getLong(MediaKeys.EXTRA_DURATION) ?: 0,
        )
    }

    private fun sync(p: Player) {
        val prev = _state.value
        val count = p.mediaItemCount
        // Queue in play order (respects shuffle).
        val order = ArrayList<Int>(count)
        if (count > 0) {
            val tl = p.currentTimeline
            if (!tl.isEmpty) {
                var i = tl.getFirstWindowIndex(p.shuffleModeEnabled)
                while (i != androidx.media3.common.C.INDEX_UNSET && order.size < count) {
                    order += i; i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
                }
            } else (0 until count).forEach { order += it }
        }
        val queue = if (prev.queue.size == count && order.size == count &&
            order.withIndex().all { (k, idx) -> prev.queue[k].index == idx && prev.queue[k].item.mediaId == p.getMediaItemAt(idx).mediaId }
        ) prev.queue else order.map { QueueEntry(it, p.getMediaItemAt(it).toNowPlaying()) }

        _state.value = prev.copy(
            current = p.currentMediaItem?.toNowPlaying(),
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            duration = p.duration.takeIf { it > 0 } ?: (p.currentMediaItem?.mediaMetadata?.extras?.getLong(MediaKeys.EXTRA_DURATION) ?: 0),
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            queue = queue,
            currentIndex = p.currentMediaItemIndex,
            error = p.playerError?.let { "Can't play this file (${it.errorCodeName})" },
        )
    }

    /** Smooth position ticks; only runs while collected. */
    fun positionFlow(intervalMs: Long = 250): Flow<Long> = flow {
        while (true) {
            emit(withContext(Dispatchers.Main) { controller?.currentPosition ?: 0L })
            delay(intervalMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // ---------- commands ----------
    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val items = songs.map { it.toMediaItem() }
        withController { c ->
            c.shuffleModeEnabled = shuffle
            val start = if (shuffle) (songs.indices).random() else startIndex.coerceIn(0, songs.lastIndex)
            c.setMediaItems(items, start, 0)
            c.prepare(); c.play()
        }
    }

    fun playOnline(tracks: List<OnlineTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        withController { c ->
            c.shuffleModeEnabled = false
            c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(0, tracks.lastIndex), 0)
            c.prepare(); c.play()
        }
    }

    fun playNext(songs: List<Song>) = withController { c ->
        val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
        c.addMediaItems(at, songs.map { it.toMediaItem() })
        if (c.mediaItemCount == songs.size) { c.prepare(); c.play() }
    }

    fun addToQueue(songs: List<Song>) = withController { c ->
        val wasEmpty = c.mediaItemCount == 0
        c.addMediaItems(songs.map { it.toMediaItem() })
        if (wasEmpty) c.prepare()
    }

    fun togglePlay() = withController { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
        if (c.isPlaying) c.pause() else c.play()
    }
    fun next() = withController { it.seekToNext() }
    fun previous() = withController { c -> if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { c -> c.seekTo(index, 0); c.play() }
    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun clearQueue() = withController { it.clearMediaItems() }
    fun stop() = withController {
        it.stop()
        it.clearMediaItems()
    }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun pause() = withController { it.pause() }

    /** Sleep timer: minutes = 0 cancels. */
    fun sleepIn(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { _state.value = _state.value.copy(sleepAt = 0); return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepAt = at)
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            withContext(Dispatchers.Main) { controller?.pause() }
            _state.value = _state.value.copy(sleepAt = 0)
        }
    }

    fun toggleFavoriteCurrent() { _state.value.current?.localId?.let(library::toggleFavorite) }

}
