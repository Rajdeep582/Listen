package com.rajdeep.listen.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rajdeep.listen.ListenApp
import com.rajdeep.listen.ui.theme.ListenTheme
import com.rajdeep.listen.ui.theme.Palette
import com.rajdeep.listen.video.PillButton
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN_PLAYER = "open_player" }

    private val vm: AppViewModel by viewModels()
    val openPlayerSignal = MutableStateFlow(0)
    private var observer: ContentObserver? = null

    // ---------- deleting media files (videos / songs) ----------
    private var pendingDelete: List<android.net.Uri> = emptyList()
    private var deleteCallback: ((Boolean) -> Unit)? = null
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val ok = r.resultCode == RESULT_OK
        if (ok && Build.VERSION.SDK_INT == 29) pendingDelete.forEach { runCatching { contentResolver.delete(it, null, null) } }
        finishDelete(ok)
    }

    private fun finishDelete(ok: Boolean) {
        if (ok) vm.rescan()
        deleteCallback?.invoke(ok); deleteCallback = null; pendingDelete = emptyList()
    }

    /** Deletes files from the device. Shows the system confirmation unless "Media management" access is granted. */
    fun deleteMedia(uris: List<android.net.Uri>, onDone: (Boolean) -> Unit) {
        if (uris.isEmpty()) return
        deleteCallback = onDone; pendingDelete = uris
        try {
            when {
                Build.VERSION.SDK_INT >= 30 -> deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(MediaStore.createDeleteRequest(contentResolver, uris).intentSender).build()
                )
                Build.VERSION.SDK_INT == 29 -> try {
                    uris.forEach { contentResolver.delete(it, null, null) }; finishDelete(true)
                } catch (e: android.app.RecoverableSecurityException) {
                    deleteLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                }
                else -> finishDelete(uris.all { runCatching { contentResolver.delete(it, null, null) > 0 }.getOrDefault(false) })
            }
        } catch (e: Exception) { finishDelete(false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        (application as ListenApp).container.player.connect()
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayerSignal.value++
        setContent { Root(vm, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerSignal.value++
    }

    fun hasMediaPermission(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Debounced rescan whenever MediaStore changes (new downloads, deletions). */
    fun startObserving() {
        if (observer != null) return
        val h = Handler(Looper.getMainLooper())
        val rescan = Runnable { vm.rescan() }
        observer = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) { h.removeCallbacks(rescan); h.postDelayed(rescan, 3000) }
        }.also {
            contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, it)
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, it)
        }
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        super.onDestroy()
    }
}

@Composable
private fun Root(vm: AppViewModel, activity: MainActivity) {
    var granted by remember { mutableStateOf(activity.hasMediaPermission()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = activity.hasMediaPermission()
        onPauseOrDispose { }
    }
    LaunchedEffect(granted) {
        if (granted) { vm.rescan(); activity.startObserving() }
    }
    ListenTheme {
        if (granted) AppRoot(vm, activity) else PermissionScreen { granted = activity.hasMediaPermission() }
    }
}

@Composable
private fun PermissionScreen(onResult: () -> Unit) {
    val perms = buildList {
        if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.READ_MEDIA_AUDIO); add(Manifest.permission.READ_MEDIA_VIDEO); add(Manifest.permission.POST_NOTIFICATIONS) }
        else add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }.toTypedArray()
    var denied by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onResult(); denied = true
    }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(com.rajdeep.listen.ui.theme.Themes.of(com.rajdeep.listen.ui.theme.Mode.MUSIC).primary.copy(0.28f), Palette.Ink, Palette.Ink))).safeDrawingPadding().padding(28.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text("listen", style = MaterialTheme.typography.displayLarge, color = Palette.Text)
            Spacer(Modifier.height(12.dp))
            Text("Your music and videos, beautifully played.", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Spacer(Modifier.height(18.dp))
            Text(
                "Listen needs access to the audio and video files on this phone to build your library. " +
                    "Files never leave your device. Notifications show playback controls.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim,
            )
            Spacer(Modifier.height(28.dp))
            PillButton("Allow access") { launcher.launch(perms) }
            if (denied) {
                Spacer(Modifier.height(14.dp))
                Text("Permission was denied. Enable 'Music and audio' and 'Videos' for Listen in system Settings → Apps.", style = MaterialTheme.typography.bodyMedium, color = Palette.Danger)
                Spacer(Modifier.height(10.dp))
                PillButton("Open app settings", filled = false) {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)))
                }
            }
        }
    }
}
