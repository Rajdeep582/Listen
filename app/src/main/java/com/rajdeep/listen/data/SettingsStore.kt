package com.rajdeep.listen.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

@Immutable
data class Settings(
    val minAudioSec: Int = 30,
    val seekStepSec: Int = 10,
    val gestureBrightness: Boolean = true,
    val gestureVolume: Boolean = true,
    val gestureSeek: Boolean = true,
    val gestureDoubleTap: Boolean = true,
    val gesturePinch: Boolean = true,
    val subSizePercent: Int = 100,
    val subColor: Int = 0xFFFFFFFF.toInt(),
    val subBgOpacity: Int = 0,
    val subOutline: Boolean = true,
    val subBold: Boolean = false,
    val subFont: String = "default",
    val subMarginDp: Int = 24,
    val prefAudioLang: String = "",
    val prefSubLang: String = "",
    val hwDecoding: Boolean = true,
    val resumeVideos: Boolean = true,
    val volumeBoost: Boolean = false,
    val reduceMotion: Boolean = false,
    val onlineEnabled: Boolean = true,
    // --- added: polish settings ---
    val eqEnabled: Boolean = false,
    val eqPreset: Int = 0,
    val bassBoost: Int = 0,          // 0..1000
    val skipSilence: Boolean = false,
    val hiddenFolders: Set<String> = emptySet(),
    val autoPlayNextVideo: Boolean = true,
    val startInVideo: Boolean = false,
    val musicTheme: Int = 0,
    val videoTheme: Int = 0,
)

class SettingsStore(private val context: Context) {
    private object K {
        val minAudio = intPreferencesKey("min_audio")
        val seekStep = intPreferencesKey("seek_step")
        val gBright = booleanPreferencesKey("g_bright")
        val gVol = booleanPreferencesKey("g_vol")
        val gSeek = booleanPreferencesKey("g_seek")
        val gDouble = booleanPreferencesKey("g_double")
        val gPinch = booleanPreferencesKey("g_pinch")
        val subSize = intPreferencesKey("sub_size")
        val subColor = intPreferencesKey("sub_color")
        val subBg = intPreferencesKey("sub_bg")
        val subOutline = booleanPreferencesKey("sub_outline")
        val subBold = booleanPreferencesKey("sub_bold")
        val subFont = stringPreferencesKey("sub_font")
        val subMargin = intPreferencesKey("sub_margin")
        val audioLang = stringPreferencesKey("audio_lang")
        val subLang = stringPreferencesKey("sub_lang")
        val hw = booleanPreferencesKey("hw")
        val resume = booleanPreferencesKey("resume")
        val boost = booleanPreferencesKey("boost")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val online = booleanPreferencesKey("online")
        val eqOn = booleanPreferencesKey("eq_on")
        val eqPreset = intPreferencesKey("eq_preset")
        val bass = intPreferencesKey("bass")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val hidden = androidx.datastore.preferences.core.stringSetPreferencesKey("hidden_folders")
        val autoNext = booleanPreferencesKey("auto_next_video")
        val startVideo = booleanPreferencesKey("start_video")
        val musicTheme = intPreferencesKey("music_theme")
        val videoTheme = intPreferencesKey("video_theme")
        // playback persistence
        val queue = stringPreferencesKey("queue")
        val queueIndex = intPreferencesKey("queue_index")
        val queuePos = longPreferencesKey("queue_pos")
        val shuffle = booleanPreferencesKey("shuffle")
        val repeat = intPreferencesKey("repeat")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings() = Settings(
        minAudioSec = this[K.minAudio] ?: 30,
        seekStepSec = this[K.seekStep] ?: 10,
        gestureBrightness = this[K.gBright] ?: true,
        gestureVolume = this[K.gVol] ?: true,
        gestureSeek = this[K.gSeek] ?: true,
        gestureDoubleTap = this[K.gDouble] ?: true,
        gesturePinch = this[K.gPinch] ?: true,
        subSizePercent = this[K.subSize] ?: 100,
        subColor = this[K.subColor] ?: 0xFFFFFFFF.toInt(),
        subBgOpacity = this[K.subBg] ?: 0,
        subOutline = this[K.subOutline] ?: true,
        subBold = this[K.subBold] ?: false,
        subFont = this[K.subFont] ?: "default",
        subMarginDp = this[K.subMargin] ?: 24,
        prefAudioLang = this[K.audioLang] ?: "",
        prefSubLang = this[K.subLang] ?: "",
        hwDecoding = this[K.hw] ?: true,
        resumeVideos = this[K.resume] ?: true,
        volumeBoost = this[K.boost] ?: false,
        reduceMotion = this[K.reduceMotion] ?: false,
        onlineEnabled = this[K.online] ?: true,
        eqEnabled = this[K.eqOn] ?: false,
        eqPreset = this[K.eqPreset] ?: 0,
        bassBoost = this[K.bass] ?: 0,
        skipSilence = this[K.skipSilence] ?: false,
        hiddenFolders = this[K.hidden] ?: emptySet(),
        autoPlayNextVideo = this[K.autoNext] ?: true,
        startInVideo = this[K.startVideo] ?: false,
        musicTheme = this[K.musicTheme] ?: 0,
        videoTheme = this[K.videoTheme] ?: 0,
    )

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.minAudio] = s.minAudioSec; p[K.seekStep] = s.seekStepSec
            p[K.gBright] = s.gestureBrightness; p[K.gVol] = s.gestureVolume; p[K.gSeek] = s.gestureSeek
            p[K.gDouble] = s.gestureDoubleTap; p[K.gPinch] = s.gesturePinch
            p[K.subSize] = s.subSizePercent; p[K.subColor] = s.subColor; p[K.subBg] = s.subBgOpacity
            p[K.subOutline] = s.subOutline; p[K.subBold] = s.subBold; p[K.subFont] = s.subFont
            p[K.subMargin] = s.subMarginDp
            p[K.audioLang] = s.prefAudioLang; p[K.subLang] = s.prefSubLang
            p[K.hw] = s.hwDecoding; p[K.resume] = s.resumeVideos; p[K.boost] = s.volumeBoost
            p[K.reduceMotion] = s.reduceMotion; p[K.online] = s.onlineEnabled
            p[K.eqOn] = s.eqEnabled; p[K.eqPreset] = s.eqPreset; p[K.bass] = s.bassBoost; p[K.skipSilence] = s.skipSilence
            p[K.hidden] = s.hiddenFolders; p[K.autoNext] = s.autoPlayNextVideo; p[K.startVideo] = s.startInVideo
            p[K.musicTheme] = s.musicTheme; p[K.videoTheme] = s.videoTheme
        }
    }

    data class SavedQueue(val ids: List<String>, val index: Int, val position: Long, val shuffle: Boolean, val repeat: Int)

    suspend fun saveQueue(q: SavedQueue) {
        context.dataStore.edit { p ->
            p[K.queue] = q.ids.joinToString("\n"); p[K.queueIndex] = q.index; p[K.queuePos] = q.position
            p[K.shuffle] = q.shuffle; p[K.repeat] = q.repeat
        }
    }

    suspend fun loadQueue(): SavedQueue? {
        val p = context.dataStore.data.first()
        val ids = p[K.queue]?.split("\n")?.filter { it.isNotBlank() } ?: return null
        if (ids.isEmpty()) return null
        return SavedQueue(ids, p[K.queueIndex] ?: 0, p[K.queuePos] ?: 0, p[K.shuffle] ?: false, p[K.repeat] ?: 0)
    }
}
