package com.rajdeep.listen.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rajdeep.listen.ListenApp
import com.rajdeep.listen.R
import com.rajdeep.listen.data.SettingsStore
import com.rajdeep.listen.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Background music engine: ExoPlayer + MediaSession (notification, lock screen, Bluetooth, Auto). */
/** Equalizer presets reported by the device, for the Settings screen. */
object AudioFx { val presets = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList()) }

@androidx.media3.common.util.UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null
    private val container get() = (application as ListenApp).container

    override fun onCreate() {
        super.onCreate()
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Listen/1.0 (personal Android player)")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://ccmixter.org/"))
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory))

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(
                DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSkipSilenceEnabled(false)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                MediaKeys.localId(mediaItem?.mediaId)?.let { container.library.recordPlay(it) }
                persist(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                persist(player)
                saveJob?.cancel()
                if (isPlaying) saveJob = scope.launch {
                    while (isActive) { delay(15_000); persist(player) }
                }
            }
        })

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setBitmapLoader(androidx.media3.session.CacheBitmapLoader(LocalArtBitmapLoader(this)))
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_listen) }
        )
        restore(player)
        observeAudioSettings(player)
    }

    // ---------- Equalizer / bass boost / skip silence ----------
    private var eq: android.media.audiofx.Equalizer? = null
    private var bass: android.media.audiofx.BassBoost? = null

    private fun attachEffects(sessionId: Int) {
        runCatching { eq?.release(); bass?.release() }
        eq = null; bass = null
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        eq = runCatching { android.media.audiofx.Equalizer(0, sessionId) }.getOrNull()
        bass = runCatching { android.media.audiofx.BassBoost(0, sessionId) }.getOrNull()
        eq?.let { e -> AudioFx.presets.value = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } }
    }

    private fun applyEffects(s: com.rajdeep.listen.data.Settings) {
        runCatching {
            eq?.let { e ->
                e.enabled = s.eqEnabled
                if (s.eqEnabled && e.numberOfPresets > 0) e.usePreset(s.eqPreset.coerceIn(0, e.numberOfPresets - 1).toShort())
            }
            bass?.let { b ->
                b.enabled = s.eqEnabled && s.bassBoost > 0
                if (b.strengthSupported && s.bassBoost > 0) b.setStrength(s.bassBoost.coerceIn(0, 1000).toShort())
            }
        }
    }

    private fun observeAudioSettings(player: ExoPlayer) {
        attachEffects(player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachEffects(audioSessionId)
                scope.launch { applyEffects(container.settings.current()) }
            }
        })
        scope.launch {
            container.settings.settings.collect { s ->
                player.skipSilenceEnabled = s.skipSilence
                applyEffects(s)
            }
        }
    }

    private fun restore(player: ExoPlayer) = scope.launch {
        val saved = container.settings.loadQueue() ?: return@launch
        val localIds = saved.ids.mapNotNull { MediaKeys.localId(it) }
        if (localIds.isEmpty() || player.mediaItemCount > 0) return@launch
        val byId = container.library.dao.songsByIds(localIds).associateBy { it.id }
        val items = localIds.mapNotNull { byId[it]?.toMediaItem() }
        if (items.isEmpty() || player.mediaItemCount > 0) return@launch
        val idx = saved.index.coerceIn(0, items.lastIndex)
        player.setMediaItems(items, idx, saved.position)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeat
        player.prepare()
    }

    private fun persist(player: Player) {
        if (player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val q = SettingsStore.SavedQueue(ids, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        container.scope.launch { container.settings.saveQueue(q) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        runCatching { eq?.release(); bass?.release() }
        session?.run { persist(player); player.release(); release() }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
