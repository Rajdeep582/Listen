package com.rajdeep.listen.video

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings as SysSettings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rajdeep.listen.ListenApp
import com.rajdeep.listen.data.Settings
import com.rajdeep.listen.data.db.Video
import com.rajdeep.listen.data.db.VideoStateEntity
import com.rajdeep.listen.ui.theme.ListenTheme
import com.rajdeep.listen.ui.theme.Mode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VideoEntry(val uri: String, val title: String, val key: String, val width: Int, val height: Int)

enum class OrientationMode(val label: String) { AUTO("Auto rotate"), LANDSCAPE("Landscape"), PORTRAIT("Portrait") }

class VideoPlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_DIMS = "dims"
        private const val EXTRA_INDEX = "index"
        private const val ACTION_PIP = "com.rajdeep.listen.PIP_TOGGLE"

        fun intent(context: Context, videos: List<Video>, index: Int): Intent {
            // Cap the queue around the selected item to keep the Intent small.
            val from = (index - 200).coerceAtLeast(0)
            val slice = videos.subList(from, (index + 200).coerceAtMost(videos.size))
            return Intent(context, VideoPlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(slice.map { it.uri }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(slice.map { it.title }))
                .putStringArrayListExtra(EXTRA_KEYS, ArrayList(slice.map { "local:${it.id}" }))
                .putExtra(EXTRA_DIMS, slice.flatMap { listOf(it.width, it.height) }.toIntArray())
                .putExtra(EXTRA_INDEX, index - from)
        }
    }

    private val container get() = (application as ListenApp).container
    lateinit var vlc: VlcController
        private set

    val playlist = mutableStateOf<List<VideoEntry>>(emptyList())
    val index = mutableIntStateOf(0)
    val inPip = mutableStateOf(false)
    val orientationMode = mutableStateOf(OrientationMode.LANDSCAPE)
    val brightness = mutableFloatStateOf(-1f)
    val settingsFlow = MutableStateFlow(Settings())

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var saveJob: Job? = null
    private var resumeOnFocus = false
    var onSubtitlePicked: ((String?) -> Unit)? = null

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "subtitle.srt"
        val saved = runCatching { vlc.loadExternalSubtitle(uri, name) }.getOrNull()
        onSubtitlePicked?.invoke(if (saved == null) "Unsupported subtitle file: $name" else null)
        saveState()
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { vlc.pause() }
    }
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { vlc.togglePlay(); updatePip() }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; vlc.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocus = vlc.isPlaying(); vlc.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) { resumeOnFocus = false; vlc.play() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        vlc = VlcController(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        brightness.floatValue = currentBrightness()

        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch { container.settings.settings.collect { settingsFlow.value = it } }

        parseIntent(intent)
        setContent { ListenTheme(Mode.VIDEO) { VideoPlayerScreen(this) } }
        openCurrent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveState()
        parseIntent(intent)
        openCurrent()
    }

    private fun parseIntent(i: Intent) {
        val uris = i.getStringArrayListExtra(EXTRA_URIS)
        if (uris != null) {
            val titles = i.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
            val keys = i.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
            val dims = i.getIntArrayExtra(EXTRA_DIMS) ?: IntArray(0)
            playlist.value = uris.mapIndexed { n, u ->
                VideoEntry(u, titles.getOrElse(n) { "Video" }, keys.getOrElse(n) { "uri:$u" }, dims.getOrElse(n * 2) { 0 }, dims.getOrElse(n * 2 + 1) { 0 })
            }
            index.intValue = i.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        } else {
            val data = i.data ?: run { finish(); return }
            val name = runCatching {
                contentResolver.query(data, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: data.lastPathSegment ?: "Video"
            playlist.value = listOf(VideoEntry(data.toString(), name.substringBeforeLast('.'), "uri:$data", 0, 0))
            index.intValue = 0
        }
    }

    val current: VideoEntry? get() = playlist.value.getOrNull(index.intValue)

    /** Opens current entry, restoring resume point / tracks / subtitle / delays from Room. */
    fun openCurrent(forceStartMs: Long? = null) {
        val e = current ?: return
        lifecycleScope.launch {
            val s = container.settings.current()
            val st = container.library.videoState(e.key)
            val start = forceStartMs ?: if (s.resumeVideos && st != null && (st.duration == 0L || st.position < st.duration - 10_000)) st.position else 0L
            applyDefaultOrientation(e.width, e.height)
            requestFocus()
            vlc.open(
                Uri.parse(e.uri), s, start,
                audio = st?.audioTrack ?: Int.MIN_VALUE, spu = st?.spuTrack ?: Int.MIN_VALUE,
                subtitleUri = st?.subtitleUri, rate = st?.rate ?: 1f,
                spuDelay = st?.spuDelayUs ?: 0, audioDelay = st?.audioDelayUs ?: 0,
            )
            vlc.setVolume(100)
            startAutoSave()
        }
    }

    /** Rebuilds libVLC in place (subtitle style change) keeping time, tracks and delays. */
    fun reloadKeepingState() {
        val e = current ?: return
        val st = vlc.snapshotState()
        val t = vlc.currentTime()
        val playing = vlc.isPlaying()
        lifecycleScope.launch {
            val s = container.settings.current()
            vlc.open(Uri.parse(e.uri), s, t, st.audioTrack, st.spuTrack, st.subtitleUri, st.rate, st.spuDelayUs, st.audioDelayUs)
            if (!playing) { delay(400); vlc.pause() }
        }
    }

    fun next(): Boolean {
        if (index.intValue >= playlist.value.lastIndex) return false
        saveState(); index.intValue += 1; openCurrent(); return true
    }

    fun previous(): Boolean {
        if (vlc.currentTime() > 5000 || index.intValue == 0) { vlc.seekTo(0); return true }
        saveState(); index.intValue -= 1; openCurrent(); return true
    }

    fun replay() = openCurrent(forceStartMs = 0)

    fun pickSubtitleFile(onResult: (String?) -> Unit) {
        onSubtitlePicked = onResult
        pickSubtitle.launch(arrayOf("application/x-subrip", "text/vtt", "text/x-ssa", "text/*", "application/octet-stream", "*/*"))
    }

    fun updateSettings(f: (Settings) -> Settings) { lifecycleScope.launch { container.settings.update(f) } }

    // ---------- persistence ----------
    fun saveState() {
        val e = current ?: return
        val st = vlc.snapshotState()
        val len = st.length
        if (len <= 0) return
        val pos = vlc.currentTime().let { if (st.ended) len else it }
        val state = VideoStateEntity(
            videoKey = e.key, position = pos, duration = len, lastWatched = System.currentTimeMillis(),
            audioTrack = st.audioTrack, spuTrack = st.spuTrack, subtitleUri = st.subtitleUri,
            spuDelayUs = st.spuDelayUs, audioDelayUs = st.audioDelayUs, rate = st.rate,
        )
        container.scope.launch { container.library.saveVideoState(state) }
    }

    private fun startAutoSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch { while (isActive) { delay(10_000); if (vlc.isPlaying()) saveState() } }
    }

    // ---------- audio focus ----------
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(focusListener)
                .build().also { focusRequest = it }
            audioManager.requestAudioFocus(req)
        }
    }

    private fun abandonFocus() { focusRequest?.let { audioManager.abandonAudioFocusRequest(it) } }

    // ---------- volume / brightness ----------
    fun maxVolume() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun systemVolume() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    fun setSystemVolume(v: Int) = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, maxVolume()), 0)

    private fun currentBrightness(): Float {
        val w = window.attributes.screenBrightness
        if (w >= 0) return w
        return runCatching { SysSettings.System.getInt(contentResolver, SysSettings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    fun setBrightness(v: Float) {
        val b = v.coerceIn(0.01f, 1f)
        brightness.floatValue = b
        window.attributes = window.attributes.apply { screenBrightness = b }
    }

    // ---------- orientation / system UI ----------
    private fun applyDefaultOrientation(w: Int, h: Int) {
        val mode = if (w > 0 && h > 0 && h > w) OrientationMode.PORTRAIT else OrientationMode.LANDSCAPE
        setOrientationMode(mode)
    }

    fun setOrientationMode(m: OrientationMode) {
        orientationMode.value = m
        requestedOrientation = when (m) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    fun onVideoSizeKnown(w: Int, h: Int) {
        if (orientationMode.value != OrientationMode.AUTO) applyDefaultOrientation(w, h)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- Picture-in-picture ----------
    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val st = vlc.snapshotState()
        val ratio = if (st.videoW > 0 && st.videoH > 0) {
            val r = st.videoW.toFloat() / st.videoH
            when { r > 2.39f -> Rational(239, 100); r < 0.42f -> Rational(42, 100); else -> Rational(st.videoW, st.videoH) }
        } else Rational(16, 9)
        val playing = vlc.isPlaying()
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "Pause" else "Play", "Toggle playback",
            PendingIntent.getBroadcast(this, 1, Intent(ACTION_PIP).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE),
        )
        return PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(listOf(action)).apply {
            if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true)
        }.build()
    }

    fun updatePip() { if (Build.VERSION.SDK_INT >= 26) pipParams()?.let { runCatching { setPictureInPictureParams(it) } } }

    /** True unless the user switched off "Picture-in-picture" for Listen in system settings. */
    fun pipAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val ops = getSystemService(android.app.AppOpsManager::class.java) ?: return true
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, applicationInfo.uid, packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, applicationInfo.uid, packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    var onPipBlocked: (() -> Unit)? = null

    fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        if (!pipAllowed()) { onPipBlocked?.invoke(); return }
        pipParams()?.let { runCatching { enterPictureInPictureMode(it) } }
    }

    fun openPipSettings() {
        runCatching { startActivity(Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.parse("package:$packageName"))) }
            .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && vlc.isPlaying() && pipAllowed()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    // ---------- lifecycle ----------
    override fun onStop() {
        super.onStop()
        // Leaving PiP (dismissed) or backgrounding: pause + persist.
        vlc.pause()
        saveState()
    }

    override fun onDestroy() {
        saveState()
        saveJob?.cancel()
        runCatching { unregisterReceiver(noisy) }
        runCatching { unregisterReceiver(pipReceiver) }
        abandonFocus()
        vlc.release()
        super.onDestroy()
    }

}
