package com.giracle.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.AppGraph
import com.giracle.android.AppPhase
import com.giracle.android.data.InAppNotice
import com.giracle.android.notify.ConnectionService
import com.giracle.android.ui.browser.ChannelBrowserScreen
import com.giracle.android.ui.channel.ChannelScreen
import com.giracle.android.ui.channel.ChannelSettingsScreen
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.TestTags
import com.giracle.android.ui.common.exposeTestTags
import com.giracle.android.ui.home.HomeScreen
import com.giracle.android.ui.inbox.InboxScreen
import com.giracle.android.ui.login.LoginScreen
import com.giracle.android.ui.manage.ManageServerScreen
import com.giracle.android.ui.members.MembersScreen
import com.giracle.android.ui.search.SearchScreen
import com.giracle.android.ui.settings.SettingsScreen
import com.giracle.android.ui.theme.GiracleTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Root(graph: AppGraph, pendingOpen: MutableState<Pair<String, String?>?>) {
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    GiracleTheme(theme = settings.theme, fontScale = settings.fontScale) {
        // testTag を resource-id として外から見えるようにする (D-016)
        Surface(Modifier.fillMaxSize().exposeTestTags(), color = MaterialTheme.colorScheme.background) {
            val phase by graph.phase.collectAsStateWithLifecycle()
            val store by graph.storeFlow.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalGraph provides graph, LocalStore provides store) {
                when (val p = phase) {
                    AppPhase.Starting -> Unit
                    AppPhase.SignedOut -> Box(Modifier.fillMaxSize().testTag(TestTags.SCREEN_LOGIN)) { LoginScreen() }
                    is AppPhase.SignedIn -> SignedIn(p, graph, pendingOpen)
                }
            }
        }
    }
}

@Composable
private fun SignedIn(p: AppPhase.SignedIn, graph: AppGraph, pendingOpen: MutableState<Pair<String, String?>?>) {
    val nav = remember { Navigator() }
    val ctx = LocalContext.current
    val store = LocalStore.current
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()

    // 通知の許可 (Android 13+)。初回ログイン時に 1 度だけ尋ねる
    var asked by rememberSaveable { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!asked && Build.VERSION.SDK_INT >= 33) {
            asked = true
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(settings.keepConnection, p.ready) {
        if (p.ready) ConnectionService.sync(ctx, settings.keepConnection)
    }

    // 前回開いていたチャンネルを復元 (起動直後に即座に表示する)
    LaunchedEffect(Unit) {
        graph.prefs.lastChannelId?.let { if (pendingOpen.value == null) nav.openChannel(it) }
    }
    // 通知から開かれたとき
    val pending = pendingOpen.value
    LaunchedEffect(pending) {
        if (pending != null) {
            nav.openChannel(pending.first, pending.second)
            pendingOpen.value = null
        }
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    CompositionLocalProvider(LocalNavigator provides nav) {
        Box(Modifier.fillMaxSize()) {
            if (!p.ready && p.error != null) {
                LoadError(p.error, onRetry = graph::retryLoad)
            } else {
                // 画面ごとに状態 (スクロール位置など) を保つため、キーを付けて描く
                val screen = nav.current
                androidx.compose.runtime.key(screen.stableKey()) {
                    Box(Modifier.fillMaxSize().testTag(screen.testTag())) {
                        when (screen) {
                            Screen.Home -> HomeScreen(loading = !p.ready)
                            is Screen.Channel -> ChannelScreen(screen.channelId, screen.messageId)
                            is Screen.ChannelSettings -> ChannelSettingsScreen(screen.channelId)
                            Screen.Inbox -> InboxScreen()
                            is Screen.Search -> SearchScreen(screen.channelId, screen.userId)
                            Screen.Members -> MembersScreen()
                            Screen.ChannelBrowser -> ChannelBrowserScreen()
                            Screen.Settings -> SettingsScreen()
                            Screen.ManageServer -> ManageServerScreen()
                        }
                    }
                }
            }
            NoticeBanner(store.notices, nav)
        }
    }
}

private fun Screen.testTag(): String = when (this) {
    Screen.Home -> TestTags.SCREEN_HOME
    is Screen.Channel -> TestTags.SCREEN_CHANNEL
    is Screen.ChannelSettings -> TestTags.SCREEN_CHANNEL_SETTINGS
    Screen.Inbox -> TestTags.SCREEN_INBOX
    is Screen.Search -> TestTags.SCREEN_SEARCH
    Screen.Members -> TestTags.SCREEN_MEMBERS
    Screen.ChannelBrowser -> TestTags.SCREEN_CHANNEL_BROWSER
    Screen.Settings -> TestTags.SCREEN_SETTINGS
    Screen.ManageServer -> TestTags.SCREEN_MANAGE_SERVER
}

private fun Screen.stableKey(): String = when (this) {
    is Screen.Channel -> "ch:$channelId"
    else -> toString()
}

@Composable
private fun LoadError(error: String, onRetry: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("サーバーから読み込めませんでした", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("再試行") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { scope.launch { graph.signOut() } }) { Text("ログアウト") }
    }
}

/** 前面にいるときの通知バナー。タップでその場所へ移動 */
@Composable
private fun NoticeBanner(flow: kotlinx.coroutines.flow.SharedFlow<InAppNotice>, nav: Navigator) {
    var current by remember { mutableStateOf<InAppNotice?>(null) }
    LaunchedEffect(flow) {
        flow.collect { n ->
            val top = nav.current
            // 今見ているチャンネルの通知は出さない
            if (top is Screen.Channel && top.channelId == n.channelId) return@collect
            current = n
        }
    }
    LaunchedEffect(current) {
        if (current != null) {
            delay(4000)
            current = null
        }
    }
    AnimatedVisibility(visible = current != null, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
        val n = current ?: return@AnimatedVisibility
        Surface(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(8.dp)
                .clickable {
                    n.channelId?.let { nav.openChannel(it, n.messageId) }
                    current = null
                },
            tonalElevation = 6.dp, shadowElevation = 6.dp, shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(n.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.body, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
