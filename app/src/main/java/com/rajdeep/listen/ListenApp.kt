package com.rajdeep.listen

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.rajdeep.listen.data.LibraryRepository
import com.rajdeep.listen.data.MediaScanner
import com.rajdeep.listen.data.SettingsStore
import com.rajdeep.listen.data.db.ListenDatabase
import com.rajdeep.listen.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI container: one instance per process. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = ListenDatabase.build(app)
    val settings = SettingsStore(app)
    val scanner = MediaScanner(app, db.dao())
    val radio = com.rajdeep.listen.data.RadioPrefs(app)
    val library = LibraryRepository(db.dao(), scanner, settings, scope)
    val player = PlayerConnection(app, library, scope)
    val recommender = com.rajdeep.listen.data.Recommender(app, db.dao())
}

class ListenApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        com.rajdeep.listen.data.online.ArtResolver.init(this)
        container.scope.launch { com.rajdeep.listen.data.CacheManager.autoTrim(this@ListenApp) }
        container.scope.launch { container.settings.settings.collect { com.rajdeep.listen.data.online.ArtResolver.enabled = it.onlineEnabled/* AI DISABLED: ; com.rajdeep.listen.data.online.AiClient.enabled = it.onlineEnabled */ } }
        // apply saved colour themes before the first frame, then follow changes
        runCatching { kotlinx.coroutines.runBlocking { container.settings.current() } }.getOrNull()?.let {
            com.rajdeep.listen.ui.theme.ThemeChoice.music = it.musicTheme; com.rajdeep.listen.ui.theme.ThemeChoice.video = it.videoTheme
        }
        container.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            container.settings.settings.collect {
                com.rajdeep.listen.ui.theme.ThemeChoice.music = it.musicTheme; com.rajdeep.listen.ui.theme.ThemeChoice.video = it.videoTheme
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                add(com.rajdeep.listen.data.LocalArtFetcher.Factory())
                add(com.rajdeep.listen.data.LocalArtKeyer())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("art")).maxSizeBytes(400L * 1024 * 1024).build() }
            .crossfade(180)
            .build()
}
