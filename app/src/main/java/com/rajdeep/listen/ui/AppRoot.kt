package com.rajdeep.listen.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rajdeep.listen.ui.music.AlbumScreen
import com.rajdeep.listen.ui.music.ArtistScreen
import com.rajdeep.listen.ui.music.ExploreScreen
import com.rajdeep.listen.ui.music.FolderSongsScreen
import com.rajdeep.listen.ui.music.HomeScreen
import com.rajdeep.listen.ui.music.LibraryScreen
import com.rajdeep.listen.ui.music.PlaylistScreen
import com.rajdeep.listen.ui.music.SongListScreen
import com.rajdeep.listen.ui.player.AddToPlaylistDialog
import com.rajdeep.listen.ui.player.ExpandedPlayer
import com.rajdeep.listen.ui.player.MiniPlayer
import com.rajdeep.listen.ui.player.SongMenuSheet
import com.rajdeep.listen.ui.search.SearchScreen
import com.rajdeep.listen.ui.settings.SettingsScreen
import com.rajdeep.listen.ui.theme.ListenTheme
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Mode
import com.rajdeep.listen.ui.theme.Palette
import com.rajdeep.listen.ui.video.VideoAllScreen
import com.rajdeep.listen.ui.video.VideoFolderScreen
import com.rajdeep.listen.ui.video.VideoHomeScreen

/** Material 3 "emphasized" curves: quick start, long graceful settle → heavier, premium feel. */
private val Heavy = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val HeavyOut = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Bottom space lists must reserve for mini-player + nav bar. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

class Nav(val controller: NavHostController) {
    /** Routes into the music graph go through this gate so they run *after* a pending switch to Music. */
    var musicGate: (action: () -> Unit) -> Unit = { it() }
    private fun music(route: String) = musicGate { controller.navigate(route) }
    fun album(id: Long) = music("album/$id")
    fun artist(name: String) = music("artist/${Uri.encode(name)}")
    fun playlist(id: Long) = music("playlist/$id")
    fun folder(path: String) = music("folder/${Uri.encode(path)}")
    fun list(kind: String) = music("list/$kind")
    fun videoFolder(path: String) = controller.navigate("vfolder/${Uri.encode(path)}")
    fun settings() = controller.navigate("settings") { launchSingleTop = true }
    fun profile() = controller.navigate("account") { launchSingleTop = true }
    fun back() = controller.popBackStack()
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val musicTabs = listOf(
    Tab("home", "Home", Icons.Rounded.Home), Tab("explore", "Explore", Icons.Rounded.Explore),
    Tab("radio", "Radio", Icons.Rounded.Radio),
    Tab("library", "Library", Icons.Rounded.LibraryMusic), Tab("search", "Search", Icons.Rounded.Search),
)
private val videoTabs = listOf(
    Tab("vhome", "For you", Icons.Rounded.Home), Tab("vall", "All videos", Icons.Rounded.VideoLibrary),
    Tab("vrecent", "History", Icons.Rounded.History), Tab("vsearch", "Search", Icons.Rounded.Search),
)

@Composable
fun AppRoot(vm: AppViewModel, activity: MainActivity) {
    // Resolve the "start in video" setting BEFORE building the UI, so the right player is the first
    // frame (no Music → Video switch on launch). Saved across rotation/process restore.
    var start by rememberSaveable { mutableStateOf<Mode?>(null) }
    LaunchedEffect(Unit) {
        if (start == null) start = if ((activity.application as com.rajdeep.listen.ListenApp).container.settings.current().startInVideo) Mode.VIDEO else Mode.MUSIC
    }
    val s = start
    if (s == null) { Box(Modifier.fillMaxSize().background(Palette.Ink)); return }
    AppContent(vm, activity, s)
}

@Composable
private fun AppContent(vm: AppViewModel, activity: MainActivity, startMode: Mode) {
    val nav = rememberNavController()
    val navApi = remember(nav) { Nav(nav) }
    var mode by rememberSaveable { mutableStateOf(startMode) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val playerState by vm.player.state.collectAsStateWithLifecycle()
    val openSignal by activity.openPlayerSignal.collectAsStateWithLifecycle()
    LaunchedEffect(openSignal) { if (openSignal > 0 && playerState.current != null) expanded = true }

    // `toggleMode` moves instantly (pill animates on its own); `mode` (theme + navigation) follows a
    // beat later so the heavy screen swap never competes with the toggle animation for frames.
    var toggleMode by rememberSaveable { mutableStateOf(mode) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val accountUser by vm.accountUser.collectAsStateWithLifecycle()
    val accountReady by vm.accountReady.collectAsStateWithLifecycle()
    if (startMode == Mode.MUSIC && !accountReady) { Box(Modifier.fillMaxSize().background(Palette.Ink)); return }
    val reduceMotion = settings.reduceMotion

    fun switchMode(m: Mode) {
        if (m == Mode.MUSIC && accountReady && accountUser == null) {
            toggleMode = Mode.MUSIC
            nav.navigate("auth") { popUpTo(nav.graph.id) { saveState = false }; launchSingleTop = true }
            return
        }
        if (m == toggleMode && m == mode) {
            // tapping the active mode returns to its front page (e.g. from Settings or a detail page)
            val front = if (m == Mode.MUSIC) "home" else "vhome"
            if (nav.currentDestination?.route != front) nav.navigate(if (m == Mode.MUSIC) "music" else "video") {
                popUpTo(nav.graph.id) { saveState = false }; launchSingleTop = true; restoreState = false
            }
            return
        }
        toggleMode = m
    }
    var pendingNav by remember { mutableStateOf<(() -> Unit)?>(null) }
    navApi.musicGate = { action ->
        if (accountReady && accountUser == null) {
            toggleMode = Mode.MUSIC
            nav.navigate("auth") { launchSingleTop = true }
        } else if (mode == Mode.MUSIC && toggleMode == Mode.MUSIC) action()
        else { pendingNav = action; toggleMode = Mode.MUSIC }
    }

    // Each mode always opens on its own front page: the back stack is cleared, never restored, so
    // e.g. Video → Settings → Music → Video lands on Video home (not on Settings).
    LaunchedEffect(toggleMode, accountReady, accountUser) {
        if (toggleMode == Mode.MUSIC && accountReady && accountUser == null) {
            mode = Mode.MUSIC
            nav.navigate("auth") {
                popUpTo(nav.graph.id) { inclusive = false; saveState = false }
                launchSingleTop = true; restoreState = false
            }
            pendingNav = null
            return@LaunchedEffect
        }
        if (toggleMode == mode) return@LaunchedEffect
        kotlinx.coroutines.delay(if (reduceMotion) 0 else 140)
        mode = toggleMode
        nav.navigate(if (toggleMode == Mode.MUSIC && accountReady && accountUser == null) "auth" else if (toggleMode == Mode.MUSIC) "music" else "video") {
            popUpTo(nav.graph.id) { inclusive = false; saveState = false }
            launchSingleTop = true; restoreState = false
        }
        pendingNav?.let { pendingNav = null; it() }
    }

    val hasMini = playerState.current != null
    val showMini = hasMini && mode == Mode.MUSIC && toggleMode == Mode.MUSIC
    val navBarInset = WindowInsets.navigationBars
    val bottomInset: Dp = (if (showMini) 72.dp else 0.dp) + 68.dp + 12.dp

    ListenTheme(mode) {
      CompositionLocalProvider(com.rajdeep.listen.ui.theme.LocalReduceMotion provides reduceMotion) {
        Box(Modifier.fillMaxSize().background(Palette.Ink)) {
            // ambient glow tinted by mode; colour animates in the draw phase only (no recomposition).
            val glow = androidx.compose.animation.animateColorAsState(
                com.rajdeep.listen.ui.theme.Themes.of(toggleMode).primary, tween(if (reduceMotion) 0 else 500), label = "glow",
            )
            val glow2 = androidx.compose.animation.animateColorAsState(
                com.rajdeep.listen.ui.theme.Themes.of(toggleMode).secondary, tween(if (reduceMotion) 0 else 700), label = "glow2",
            )
            // Colour wash: top-left primary bloom + top-right secondary bloom + soft floor tint. Draw phase only.
            Box(Modifier.fillMaxSize().drawBehind {
                val w = size.width; val h = size.height
                drawRect(Brush.verticalGradient(listOf(glow.value.copy(alpha = 0.26f), glow.value.copy(alpha = 0.08f), Color.Transparent), endY = h * 0.62f))
                drawRect(Brush.radialGradient(listOf(glow.value.copy(alpha = 0.30f), Color.Transparent), Offset(w * 0.12f, h * 0.04f), w * 0.95f))
                drawRect(Brush.radialGradient(listOf(glow2.value.copy(alpha = 0.18f), Color.Transparent), Offset(w * 0.95f, h * 0.12f), w * 0.75f))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, glow.value.copy(alpha = 0.10f)), startY = h * 0.7f))
            })

            CompositionLocalProvider(LocalBottomInset provides bottomInset) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(toggleMode, ::switchMode, onSettings = navApi::settings, onAccount = navApi::profile, showAccount = accountUser != null)
                    NavHost(
                        navController = nav, startDestination = if (startMode == Mode.VIDEO) "video" else if (accountUser == null) "auth" else "music", modifier = Modifier.weight(1f),
                        // Weighted, slightly slow motion (M3 "emphasized" curve): content settles in rather than snapping.
                        enterTransition = {
                            when {
                                reduceMotion -> fadeIn(tween(120))
                                isModeSwitch() -> fadeIn(tween(420, delayMillis = 90, easing = Heavy)) + slideInHorizontally(tween(620, easing = Heavy)) { w -> if (targetIsVideo()) w / 5 else -w / 5 } + scaleIn(tween(620, easing = Heavy), initialScale = 0.97f)
                                else -> fadeIn(tween(380, delayMillis = 70, easing = Heavy)) + scaleIn(tween(520, easing = Heavy), initialScale = 0.955f) + slideInVertically(tween(520, easing = Heavy)) { it / 18 }
                            }
                        },
                        exitTransition = {
                            when {
                                reduceMotion -> fadeOut(tween(90))
                                isModeSwitch() -> fadeOut(tween(260, easing = HeavyOut)) + slideOutHorizontally(tween(560, easing = Heavy)) { w -> if (targetIsVideo()) -w / 7 else w / 7 } + scaleOut(tween(560, easing = Heavy), targetScale = 0.97f)
                                else -> fadeOut(tween(240, easing = HeavyOut)) + scaleOut(tween(480, easing = Heavy), targetScale = 1.025f)
                            }
                        },
                        popEnterTransition = {
                            if (reduceMotion) fadeIn(tween(120))
                            else fadeIn(tween(380, delayMillis = 60, easing = Heavy)) + scaleIn(tween(500, easing = Heavy), initialScale = 1.03f)
                        },
                        popExitTransition = {
                            if (reduceMotion) fadeOut(tween(90))
                            else fadeOut(tween(240, easing = HeavyOut)) + scaleOut(tween(480, easing = Heavy), targetScale = 0.955f) + slideOutVertically(tween(480, easing = Heavy)) { it / 18 }
                        },
                    ) {
                        musicGraph(vm, navApi)
                        videoGraph(vm, navApi, activity)
                        composable("auth") {
                            AccountScreen(vm, onAuthenticated = {
                                toggleMode = Mode.MUSIC
                                nav.navigate("music") { popUpTo(nav.graph.id) { inclusive = false }; launchSingleTop = true }
                            }, onSignOut = {})
                        }
                        composable("account") {
                            if (accountUser == null) AccountScreen(vm, onAuthenticated = {
                                nav.navigate("music") { launchSingleTop = true }
                            }, onSignOut = {})
                            else ProfileScreen(vm, accountUser, onSignOut = {
                                vm.signOut(); nav.navigate("auth") { popUpTo(nav.graph.id) { inclusive = false }; launchSingleTop = true }
                            })
                        }
                        composable("settings") { SettingsScreen(vm, activity) }
                    }
                }
            }

            // bottom chrome
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                AnimatedVisibility(showMini, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    MiniPlayer(vm.player, playerState, onExpand = { expanded = true }, modifier = Modifier.padding(horizontal = 10.dp))
                }
                androidx.compose.animation.AnimatedContent(
                    toggleMode, label = "tabs",
                    transitionSpec = { (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120))) },
                ) { m -> if (m == Mode.VIDEO || accountUser != null) BottomNav(if (m == Mode.MUSIC) musicTabs else videoTabs, nav) }
                Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(navBarInset).background(Palette.Ink.copy(alpha = 0.96f)))
            }

            AnimatedVisibility(
                expanded && hasMini,
                enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(280)),
            ) {
                ListenTheme(Mode.MUSIC) {
                    ExpandedPlayer(vm, playerState, onCollapse = { expanded = false }, onOpenAlbum = { id -> expanded = false; navApi.album(id) }, onOpenArtist = { a -> expanded = false; navApi.artist(a) })
                }
            }
            BackHandler(expanded) { expanded = false }

            SongMenuSheet(vm, navApi, onNavigated = { expanded = false })
            AddToPlaylistDialog(vm)
            BackgroundPlaybackPrompt(playerState.isPlaying)
            com.rajdeep.listen.ui.video.VideoMenuSheet(activity)
        }
      }
    }
}

private fun graphOf(d: androidx.navigation.NavDestination): String? {
    var p: androidx.navigation.NavDestination? = d
    while (p != null) { if (p.route == "music" || p.route == "video") return p.route; p = p.parent }
    return null
}
private fun androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isModeSwitch(): Boolean {
    val a = graphOf(initialState.destination); val b = graphOf(targetState.destination)
    return a != null && b != null && a != b
}
private fun androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.targetIsVideo() = graphOf(targetState.destination) == "video"

private fun NavGraphBuilder.musicGraph(vm: AppViewModel, nav: Nav) {
    navigation(startDestination = "home", route = "music") {
        composable("home") { HomeScreen(vm, nav) }
        composable("explore") { ExploreScreen(vm) }
        composable("radio") { com.rajdeep.listen.ui.radio.RadioScreen(vm) }
        composable("library") { LibraryScreen(vm, nav) }
        composable("search") { SearchScreen(vm, nav, Mode.MUSIC) }
        composable("album/{id}", listOf(navArgument("id") { type = NavType.LongType })) { AlbumScreen(vm, nav, it.arguments!!.getLong("id")) }
        composable("artist/{name}") { ArtistScreen(vm, nav, Uri.decode(it.arguments!!.getString("name")!!)) }
        composable("playlist/{id}", listOf(navArgument("id") { type = NavType.LongType })) { PlaylistScreen(vm, nav, it.arguments!!.getLong("id")) }
        composable("folder/{path}") { FolderSongsScreen(vm, nav, Uri.decode(it.arguments!!.getString("path")!!)) }
        composable("list/{kind}") { SongListScreen(vm, nav, it.arguments!!.getString("kind")!!) }
    }
}

private fun NavGraphBuilder.videoGraph(vm: AppViewModel, nav: Nav, activity: MainActivity) {
    navigation(startDestination = "vhome", route = "video") {
        composable("vhome") { VideoHomeScreen(vm, nav, activity) }
        composable("vall") { VideoAllScreen(vm, activity, historyOnly = false) }
        composable("vrecent") { VideoAllScreen(vm, activity, historyOnly = true) }
        composable("vsearch") { SearchScreen(vm, nav, Mode.VIDEO) }
        composable("vfolder/{path}") { VideoFolderScreen(vm, nav, activity, Uri.decode(it.arguments!!.getString("path")!!)) }
    }
}

@Composable
private fun TopBar(mode: Mode, onMode: (Mode) -> Unit, onSettings: () -> Unit, onAccount: () -> Unit, showAccount: Boolean) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("listen", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
        Box(Modifier.size(6.dp).offset(x = 2.dp, y = 6.dp).clip(CircleShape).background(LocalAccent.current.primary))
        Spacer(Modifier.weight(1f))
        ModeToggle(mode, onMode)
        Spacer(Modifier.width(4.dp))
        if (mode == Mode.MUSIC && showAccount) {
            IconButton(
                onClick = onAccount,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Palette.SurfaceHigh),
            ) {
                Icon(Icons.Rounded.AccountCircle, "Profile", tint = LocalAccent.current.primary, modifier = Modifier.size(28.dp))
            }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = Palette.TextDim) }
    }
}

@Composable
private fun ModeToggle(mode: Mode, onMode: (Mode) -> Unit) {
    val segW = 74.dp
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduce = com.rajdeep.listen.ui.theme.LocalReduceMotion.current
    // 0f = MUSIC, 1f = VIDEO. Read only inside graphicsLayer/drawBehind → animates without recomposition.
    val t = androidx.compose.animation.core.animateFloatAsState(
        if (mode == Mode.MUSIC) 0f else 1f,
        if (reduce) tween(0) else spring(dampingRatio = 0.74f, stiffness = 420f), label = "toggle",
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val segPx = with(density) { segW.toPx() }
    val mSpec = com.rajdeep.listen.ui.theme.Themes.of(Mode.MUSIC)
    val vSpec = com.rajdeep.listen.ui.theme.Themes.of(Mode.VIDEO)
    Box(Modifier.clip(CircleShape).background(Palette.SurfaceHigh).padding(4.dp)) {
        Box(
            Modifier.width(segW).height(34.dp)
                .graphicsLayer {
                    translationX = t.value * segPx
                    // subtle stretch while moving, like a liquid pill
                    val mid = 1f - kotlin.math.abs(t.value - 0.5f) * 2f
                    scaleX = 1f + 0.12f * mid.coerceIn(0f, 1f)
                }
                .clip(CircleShape)
                .drawBehind {
                    val a = androidx.compose.ui.graphics.lerp(mSpec.primary, vSpec.primary, t.value.coerceIn(0f, 1f))
                    val b = androidx.compose.ui.graphics.lerp(mSpec.secondary, vSpec.secondary, t.value.coerceIn(0f, 1f))
                    drawRect(Brush.horizontalGradient(listOf(a, b)))
                },
        )
        Row {
            Mode.entries.forEach { m ->
                val selected = m == mode
                val c by androidx.compose.animation.animateColorAsState(if (selected) Palette.Ink else Palette.TextDim, tween(180), label = "tc")
                Box(
                    Modifier.width(segW).height(34.dp).clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, null) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onMode(m)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(m.name, style = MaterialTheme.typography.labelLarge, color = c)
                }
            }
        }
    }
}

@Composable
private fun BottomNav(tabs: List<Tab>, nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val accent = LocalAccent.current
    val selectedIdx = tabs.indexOfFirst { it.route == current }
    val reduce = com.rajdeep.listen.ui.theme.LocalReduceMotion.current
    // index as float: pill glides between tabs with a weighty spring
    val pos by androidx.compose.animation.core.animateFloatAsState(
        selectedIdx.coerceAtLeast(0).toFloat(),
        if (reduce) tween(0) else spring(dampingRatio = 0.82f, stiffness = 180f), label = "navpill",
    )
    val pillAlpha by androidx.compose.animation.core.animateFloatAsState(if (selectedIdx >= 0) 1f else 0f, tween(300), label = "pa")
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.Ink.copy(alpha = 0.0f), Palette.Ink.copy(alpha = 0.94f), Palette.Ink.copy(alpha = 0.97f))))
            .drawBehind { drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.primary.copy(alpha = 0.10f)))) }
            .padding(top = 10.dp, bottom = 2.dp).height(58.dp),
    ) {
        val tabW = maxWidth / tabs.size
        // sliding indicator (draw phase)
        Box(
            Modifier.width(tabW).height(32.dp).graphicsLayer { translationX = tabW.toPx() * pos; alpha = pillAlpha },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(62.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(listOf(accent.primary.copy(alpha = 0.30f), accent.secondary.copy(alpha = 0.22f)))))
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
            tabs.forEachIndexed { i, t ->
                val selected = i == selectedIdx
                val lift by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 260f), label = "lift")
                val tint by androidx.compose.animation.animateColorAsState(if (selected) accent.primary else Palette.TextFaint, tween(380), label = "tint")
                Column(
                    Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null) {
                        if (selected) return@clickable
                        nav.navigate(t.route) {
                            val parent = entry?.destination?.parent
                            popUpTo(if (parent != null && parent.id != nav.graph.id) parent.findStartDestination().id else nav.graph.id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                        Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(24.dp).graphicsLayer { val sc = 1f + 0.12f * lift; scaleX = sc; scaleY = sc; translationY = -2f * lift })
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, style = MaterialTheme.typography.labelMedium, color = if (selected) Palette.Text else Palette.TextFaint)
                }
            }
        }
    }
}

private fun androidx.navigation.NavGraph.findStartDestination(): androidx.navigation.NavDestination {
    var d: androidx.navigation.NavDestination = this
    while (d is androidx.navigation.NavGraph) d = d.findNode(d.startDestinationId) ?: break
    return d
}


/**
 * Asks once (the first time music plays) to exempt Listen from battery optimisation, so aggressive
 * OEM battery savers don't kill background playback. Never shown again after Allow / Not now.
 */
@android.annotation.SuppressLint("BatteryLife")
@Composable
private fun BackgroundPlaybackPrompt(isPlaying: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("prompts", android.content.Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying || prefs.getBoolean("bg_asked", false)) return@LaunchedEffect
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) { prefs.edit().putBoolean("bg_asked", true).apply(); return@LaunchedEffect }
        kotlinx.coroutines.delay(2500); show = true
    }
    if (!show) return
    val accent = LocalAccent.current.primary
    fun done() { prefs.edit().putBoolean("bg_asked", true).apply(); show = false }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = ::done, containerColor = Palette.SurfaceHigh,
        title = { Text("Keep music playing in the background?", color = Palette.Text) },
        text = { Text("Some phones stop music when the screen is off to save battery. Allow Listen to run in the background so playback never cuts out.", color = Palette.TextDim) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))) }
                done()
            }) { Text("Allow", color = accent) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = ::done) { Text("Not now", color = Palette.TextDim) } },
    )
}
