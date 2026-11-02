package com.rajdeep.listen.video

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Immutable
import com.rajdeep.listen.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

@Immutable
data class Track(val id: Int, val name: String, val language: String?, val detail: String?)

enum class Scale(val label: String, val vlc: MediaPlayer.ScaleType?, val aspect: String?) {
    BEST_FIT("Best fit", MediaPlayer.ScaleType.SURFACE_BEST_FIT, null),
    FIT_SCREEN("Fit screen", MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, null),
    FILL("Stretch", MediaPlayer.ScaleType.SURFACE_FILL, null),
    ORIGINAL("Original", MediaPlayer.ScaleType.SURFACE_ORIGINAL, null),
    R16_9("16:9", null, "16:9"),
    R4_3("4:3", null, "4:3"),
    R21_9("21:9", null, "21:9"),
    R1_1("1:1", null, "1:1"),
}

@Immutable
data class VideoUiState(
    val isPlaying: Boolean = false,
    val buffering: Boolean = true,
    val length: Long = 0,
    val ended: Boolean = false,
    val error: String? = null,
    val audioTracks: List<Track> = emptyList(),
    val spuTracks: List<Track> = emptyList(),
    val audioTrack: Int = -1,
    val spuTrack: Int = -1,
    val rate: Float = 1f,
    val spuDelayUs: Long = 0,
    val audioDelayUs: Long = 0,
    val scale: Scale = Scale.BEST_FIT,
    val videoW: Int = 0,
    val videoH: Int = 0,
    val codecInfo: String = "",
    val subtitleUri: String? = null,
    val seekable: Boolean = true,
)

/**
 * Thin, lifecycle-safe wrapper over libVLC. Owns LibVLC + MediaPlayer; can be rebuilt in place
 * (used when subtitle style changes, since libVLC freetype options are init-time only).
 */
class VlcController(private val context: Context) {

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var layout: VLCVideoLayout? = null
    private var currentUri: Uri? = null

    private val _state = MutableStateFlow(VideoUiState())
    val state: StateFlow<VideoUiState> = _state.asStateFlow()
    private val _time = MutableStateFlow(0L)
    val time: StateFlow<Long> = _time.asStateFlow()

    /** Pending restore applied once tracks are known (ESAdded / Playing). */
    private var pendingAudio = Int.MIN_VALUE
    private var pendingSpu = Int.MIN_VALUE
    private var prefAudioLang = ""
    private var prefSubLang = ""
    private var restoredTracks = false

    private fun buildOptions(s: Settings): ArrayList<String> = arrayListOf(
        "--no-drop-late-frames", "--no-skip-frames",
        "--audio-time-stretch",
        "--avcodec-skiploopfilter=1",
        "--sub-text-scale=${s.subSizePercent.coerceIn(30, 400)}",
        "--freetype-color=${s.subColor and 0xFFFFFF}",
        "--freetype-opacity=${(s.subColor ushr 24) and 0xFF}",
        "--freetype-background-color=0",
        "--freetype-background-opacity=${s.subBgOpacity.coerceIn(0, 255)}",
        "--freetype-outline-thickness=${if (s.subOutline) 4 else 0}",
        "--freetype-shadow-opacity=${if (s.subOutline) 128 else 0}",
        "--sub-margin=${s.subMarginDp.coerceAtLeast(0) * context.resources.displayMetrics.density.toInt()}",
        "--sub-autodetect-fuzzy=3",
        "--verbose=0",
    ).apply {
        if (s.subBold) add("--freetype-bold")
        when (s.subFont) {
            "serif" -> add("--freetype-font=Noto Serif")
            "mono" -> add("--freetype-font=Droid Sans Mono")
            "condensed" -> add("--freetype-font=Roboto Condensed")
        }
    }

    fun attach(view: VLCVideoLayout) {
        layout = view
        player?.let { if (!it.vlcVout.areViewsAttached()) it.attachViews(view, null, true, true) }
    }

    fun detachViews() { player?.detachViews() }

    fun open(uri: Uri, settings: Settings, startMs: Long, audio: Int, spu: Int, subtitleUri: String?, rate: Float, spuDelay: Long, audioDelay: Long) {
        releasePlayer()
        currentUri = uri
        pendingAudio = audio; pendingSpu = spu; restoredTracks = false
        prefAudioLang = settings.prefAudioLang.lowercase(); prefSubLang = settings.prefSubLang.lowercase()
        _state.value = VideoUiState(rate = rate, spuDelayUs = spuDelay, audioDelayUs = audioDelay, scale = _state.value.scale, subtitleUri = subtitleUri)
        _time.value = startMs

        val lib = LibVLC(context, buildOptions(settings)).also { libVlc = it }
        val mp = MediaPlayer(lib).also { player = it }
        mp.setEventListener(::onEvent)
        layout?.let { mp.attachViews(it, null, true, true) }

        val media = when (uri.scheme) {
            "content" -> {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                Media(lib, pfd!!.fileDescriptor)
            }
            else -> Media(lib, uri)
        }
        media.setHWDecoderEnabled(settings.hwDecoding, false)
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        mp.media = media
        media.release()
        subtitleUri?.let { mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(it), true) }
        applyScale(_state.value.scale)
        mp.play()
        mp.rate = rate
        if (spuDelay != 0L) mp.spuDelay = spuDelay
        if (audioDelay != 0L) mp.audioDelay = audioDelay
    }

    private fun onEvent(e: MediaPlayer.Event) {
        val mp = player ?: return
        when (e.type) {
            MediaPlayer.Event.Playing -> { update { copy(isPlaying = true, buffering = false, ended = false, error = null) }; refreshTracks() }
            MediaPlayer.Event.Paused -> update { copy(isPlaying = false) }
            MediaPlayer.Event.Stopped -> update { copy(isPlaying = false) }
            MediaPlayer.Event.EndReached -> update { copy(isPlaying = false, ended = true) }
            MediaPlayer.Event.EncounteredError -> update { copy(isPlaying = false, buffering = false, error = "This file can't be played") }
            MediaPlayer.Event.Buffering -> update { copy(buffering = e.buffering < 100f) }
            MediaPlayer.Event.TimeChanged -> _time.value = e.timeChanged
            MediaPlayer.Event.LengthChanged -> update { copy(length = e.lengthChanged) }
            MediaPlayer.Event.SeekableChanged -> update { copy(seekable = e.seekable) }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected, MediaPlayer.Event.Vout -> refreshTracks()
        }
        if (_state.value.length == 0L && mp.length > 0) update { copy(length = mp.length) }
    }

    private inline fun update(f: VideoUiState.() -> VideoUiState) { _state.value = _state.value.f() }

    private fun mediaTrackInfo(): Pair<Map<Int, IMedia.Track>, String> {
        val media = player?.media ?: return emptyMap<Int, IMedia.Track>() to ""
        val map = HashMap<Int, IMedia.Track>()
        val info = StringBuilder()
        try {
            for (i in 0 until media.trackCount) {
                val t = media.getTrack(i) ?: continue
                map[t.id] = t
                when (t) {
                    is IMedia.VideoTrack -> info.append("Video: ${t.codec} ${t.width}×${t.height}" + (if (t.frameRateDen > 0) " @ ${"%.2f".format(t.frameRateNum.toFloat() / t.frameRateDen)} fps" else "") + "\n")
                    is IMedia.AudioTrack -> info.append("Audio #${t.id}: ${t.codec} ${t.channels}ch ${t.rate} Hz ${t.language ?: ""}\n")
                    is IMedia.SubtitleTrack -> info.append("Subtitle #${t.id}: ${t.codec} ${t.language ?: ""}\n")
                }
            }
        } finally { media.release() }
        return map to info.toString().trim()
    }

    private fun refreshTracks() {
        val mp = player ?: return
        val (meta, info) = mediaTrackInfo()
        fun list(arr: Array<MediaPlayer.TrackDescription>?) = arr.orEmpty().map { d ->
            val t = meta[d.id]
            val lang = t?.language?.takeIf { it.isNotBlank() }
            val detail = when (t) {
                is IMedia.AudioTrack -> listOfNotNull(t.codec?.uppercase(), if (t.channels > 0) "${t.channels}ch" else null, if (t.rate > 0) "${t.rate / 1000} kHz" else null).joinToString(" · ")
                is IMedia.SubtitleTrack -> t.codec?.uppercase()
                else -> null
            }
            Track(d.id, d.name ?: "Track ${d.id}", lang, detail)
        }
        val audio = list(mp.audioTracks)
        val spu = list(mp.spuTracks)
        val vt = mp.currentVideoTrack
        update {
            copy(
                audioTracks = audio, spuTracks = spu, audioTrack = mp.audioTrack, spuTrack = mp.spuTrack,
                videoW = vt?.width ?: videoW, videoH = vt?.height ?: videoH, codecInfo = info.ifEmpty { codecInfo },
            )
        }
        if (!restoredTracks && (audio.isNotEmpty() || spu.isNotEmpty())) {
            restoredTracks = true
            restoreTrackChoice(audio, spu)
        }
    }

    private fun matchesLang(t: Track, lang: String) =
        lang.isNotEmpty() && ((t.language?.lowercase()?.startsWith(lang) == true) || t.name.lowercase().contains(lang))

    private fun restoreTrackChoice(audio: List<Track>, spu: List<Track>) {
        val mp = player ?: return
        when {
            pendingAudio != Int.MIN_VALUE && audio.any { it.id == pendingAudio } -> mp.audioTrack = pendingAudio
            prefAudioLang.isNotEmpty() -> audio.firstOrNull { it.id >= 0 && matchesLang(it, prefAudioLang) }?.let { mp.audioTrack = it.id }
        }
        when {
            pendingSpu != Int.MIN_VALUE && (pendingSpu == -1 || spu.any { it.id == pendingSpu }) -> mp.spuTrack = pendingSpu
            prefSubLang.isNotEmpty() -> spu.firstOrNull { it.id >= 0 && matchesLang(it, prefSubLang) }?.let { mp.spuTrack = it.id }
        }
        update { copy(audioTrack = mp.audioTrack, spuTrack = mp.spuTrack) }
    }

    // ---------- commands ----------
    /** Returns false when playback has ended (caller decides: replay or next). */
    fun togglePlay(): Boolean {
        val mp = player ?: return true
        if (_state.value.ended) return false
        if (mp.isPlaying) mp.pause() else mp.play()
        return true
    }
    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun isPlaying() = player?.isPlaying == true
    fun seekTo(ms: Long) {
        val mp = player ?: return
        val len = _state.value.length
        val t = if (len > 0) ms.coerceIn(0, len - 500) else ms.coerceAtLeast(0)
        mp.setTime(t, true)
        _time.value = t
    }
    fun seekBy(deltaMs: Long) = seekTo(currentTime() + deltaMs)
    fun currentTime(): Long = player?.time?.takeIf { it >= 0 } ?: _time.value
    fun length(): Long = _state.value.length

    fun setAudioTrack(id: Int) { player?.audioTrack = id; update { copy(audioTrack = id) } }
    fun setSpuTrack(id: Int) { player?.spuTrack = id; update { copy(spuTrack = id) } }
    fun setRate(r: Float) { player?.rate = r; update { copy(rate = r) } }
    fun setSpuDelay(us: Long) { player?.spuDelay = us; update { copy(spuDelayUs = us) } }
    fun setAudioDelay(us: Long) { player?.audioDelay = us; update { copy(audioDelayUs = us) } }
    fun setVolume(percent: Int) { player?.volume = percent.coerceIn(0, 200) }

    fun applyScale(s: Scale) {
        val mp = player
        if (mp != null) {
            if (s.vlc != null) { mp.aspectRatio = null; mp.videoScale = s.vlc }
            else { mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT; mp.aspectRatio = s.aspect }
        }
        update { copy(scale = s) }
    }

    /** Copies an external subtitle into app cache (libVLC can't read SAF content:// URIs) and attaches it. */
    fun loadExternalSubtitle(uri: Uri, displayName: String): String? {
        val ext = displayName.substringAfterLast('.', "srt").lowercase()
        if (ext !in setOf("srt", "vtt", "ass", "ssa", "sub", "txt", "smi", "idx")) return null
        val dir = File(context.filesDir, "subs").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            if (input.available() > 8 * 1024 * 1024) return null
            out.outputStream().use { input.copyTo(it) }
        } ?: return null
        val fileUri = Uri.fromFile(out)
        player?.addSlave(IMedia.Slave.Type.Subtitle, fileUri, true)
        update { copy(subtitleUri = fileUri.toString()) }
        return fileUri.toString()
    }

    fun snapshotState(): VideoUiState = _state.value

    private fun releasePlayer() {
        player?.let { it.setEventListener(null); it.stop(); it.detachViews(); it.release() }
        player = null
        libVlc?.release(); libVlc = null
        runCatching { pfd?.close() }; pfd = null
    }

    fun release() { releasePlayer(); layout = null }
}
