package com.giracle.android.ui.channel

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.giracle.android.ui.common.exposeTestTags
import com.giracle.android.ui.common.TestTags
import com.giracle.android.data.ChatStore
import com.giracle.android.data.model.FileAttached
import com.giracle.android.data.model.Message
import com.giracle.android.ui.LocalNavigator
import com.giracle.android.ui.Screen
import com.giracle.android.ui.common.AppIcons
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.fullTime
import com.giracle.android.ui.common.rememberUser
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.userMessage
import com.giracle.android.ui.members.UserSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(channelId: String, jumpTo: String?) {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val history by store.history(channelId).collectAsStateWithLifecycle()
    val channels by store.channels.collectAsStateWithLifecycle()
    val me by store.me.collectAsStateWithLifecycle()
    val muted by store.mutedChannels.collectAsStateWithLifecycle()
    val readBefore by store.readTimesBefore.collectAsStateWithLifecycle()
    val inbox by store.inbox.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    val channel = channels.firstOrNull { it.id == channelId }
    val joined = me?.channelJoin?.any { it.channelId == channelId } == true

    val listState = rememberLazyListState()
    var error by remember { mutableStateOf<String?>(null) }
    var highlight by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var pickerFor by remember { mutableStateOf<Message?>(null) }
    var whoReacted by remember { mutableStateOf<Pair<Message, String>?>(null) }
    var imageUrl by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Message?>(null) }
    var userSheet by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Message?>(null) }

    // ---- 開く・離れる ----
    LaunchedEffect(channelId) {
        graph.prefs.lastChannelId = channelId
        // 開いたチャンネルの通知は、もう要らないので消す
        com.giracle.android.notify.Notifier.cancelChannel(ctx, channelId)
        if (channel == null) runCatching { store.channels.value = store.channels.value + graph.api.channelInfo(channelId) }
        try {
            store.openChannel(channelId)
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    DisposableEffect(channelId) { onDispose { store.leaveChannel(channelId) } }

    // ---- 指定メッセージへ移動 ----
    suspend fun jump(messageId: String) {
        val ok = store.jumpToMessage(channelId, messageId)
        if (!ok) { ctx.toast("対象のメッセージを取得できませんでした。削除された可能性があります。"); return }
        val idx = store.history(channelId).value.messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            listState.scrollToItem(idx)
            highlight = messageId
            delay(2500)
            highlight = null
        }
    }
    LaunchedEffect(jumpTo) { if (jumpTo != null) jump(jumpTo) }

    // ---- 初回表示位置: 新着線の少し下 (未読の始まりが見えるように) ----
    var positioned by remember(channelId) { mutableStateOf(jumpTo != null) }
    LaunchedEffect(history.loaded) {
        if (!history.loaded || positioned) return@LaunchedEffect
        positioned = true
        val before = readBefore[channelId]
        if (before.isNullOrEmpty()) return@LaunchedEffect
        val firstUnread = history.messages.indexOfLast { ChatStore.isAfter(it.createdAt, before) }
        if (firstUnread > 2) listState.scrollToItem(firstUnread - 2)
    }

    // ---- ページング: 端に近づいたら続きを読む ----
    LaunchedEffect(channelId) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            Triple(first, last, info.totalItemsCount)
        }.distinctUntilChanged().collect { (first, last, total) ->
            val s = store.history(channelId).value
            if (!s.loaded) return@collect
            try {
                if (!s.atTop && total > 0 && last >= total - 8) store.loadOlder(channelId)
                if (!s.atEnd && first <= 3) store.loadNewer(channelId)
            } catch (e: Exception) {
                error = e.userMessage()
            }
        }
    }

    // ---- 既読: 最新を表示しているとき ----
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 200 } }
    LaunchedEffect(channelId) {
        snapshotFlow { atBottom to store.history(channelId).value.messages.firstOrNull()?.id }
            .filter { it.first }
            .collect { store.markRead(channelId) }
    }
    // 自分の送信・新着で最下部にいれば追従する
    val newestId = history.messages.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && listState.firstVisibleItemIndex <= 1 && history.atEnd) listState.animateScrollToItem(0)
    }

    // メンションされたメッセージを表示したら通知を既読にする (Web 版 MentionReadWrapper)
    LaunchedEffect(channelId, inbox, history.version) {
        val ids = history.messages.mapTo(HashSet()) { it.id }
        inbox.filter { it.message.channelId == channelId && it.message.id in ids }.forEach { store.readInbox(it.message.id) }
    }

    val cb = remember(channelId) {
        MessageCallbacks(
            onLongPress = { actionsFor = it },
            onUserClick = { userSheet = it },
            onChannelClick = { nav.openChannel(it) },
            onMessageLink = { ch, id -> if (ch == channelId) scope.launch { jump(id) } else nav.openChannel(ch, id) },
            onToggleReaction = { m, code -> scope.launch { runCatching { store.toggleReaction(m, code) }.onFailure { ctx.toast(it.userMessage()) } } },
            onReactionLongPress = { m, code -> whoReacted = m to code },
            onImageClick = { imageUrl = it },
            onFileDownload = { f -> download(ctx, graph.api.fileUrl(f.id), f, graph.prefs.token) },
            onExpand = { expanded = it },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                title = {
                    Column(Modifier.clickable { nav.push(Screen.ChannelSettings(channelId)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (channel?.viewableRole?.isNotEmpty() == true) Icons.Default.Lock else AppIcons.Tag, null, Modifier.size(18.dp))
                            Text(channel?.name ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        }
                        if (!channel?.description.isNullOrBlank()) {
                            Text(channel.description, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { runCatching { store.setMuted(channelId, channelId !in muted) }.onFailure { ctx.toast(it.userMessage()) } } }) {
                        if (channelId in muted) Icon(AppIcons.NotificationsOff, "ミュート解除") else Icon(Icons.Default.Notifications, "ミュート")
                    }
                    IconButton(onClick = { nav.push(Screen.Search(channelId = channelId)) }) { Icon(Icons.Default.Search, "このチャンネルを検索") }
                    IconButton(onClick = { nav.push(Screen.ChannelSettings(channelId)) }) { Icon(Icons.Default.Info, "チャンネル情報") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!history.loaded) {
                    if (error != null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!)
                        TextButton(onClick = { error = null; scope.launch { runCatching { store.openChannel(channelId) }.onFailure { error = it.userMessage() } } }) { Text("再試行") }
                    } else CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    val messages = history.messages
                    val before = readBefore[channelId]
                    LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize().testTag(TestTags.CHANNEL_MESSAGES)) {
                        if (!history.atEnd) item(key = "newer", contentType = "loader") { Loader() }
                        itemsIndexed(messages, key = { _, m -> m.id }, contentType = { _, m -> if (m.isSystemMessage) "sys" else "msg" }) { i, m ->
                            val older = messages.getOrNull(i + 1)
                            val newer = messages.getOrNull(i - 1)
                            // 新着線: この行が既読最後で、より新しい行がある
                            val newLine = before != null && before.isNotEmpty() && newer != null && m.createdAt == before && history.atEnd
                            MessageItem(
                                m = m,
                                showHeader = !sameSender(m, older) || m.replyingMessageId != null,
                                showDate = older == null || !com.giracle.android.ui.common.sameDay(m.createdAt, older.createdAt),
                                newLineBelow = newLine,
                                highlighted = m.id == highlight,
                                gap = settings.messageGapLevel,
                                cb = cb,
                            )
                        }
                        item(key = "top", contentType = "loader") {
                            if (history.atTop) Text("履歴の末端まで到達しました。", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            else Loader()
                        }
                    }
                    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 10 } }
                    if (!history.atEnd || showJump) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    if (!store.history(channelId).value.atEnd) runCatching { store.jumpToLatest(channelId) }
                                    listState.scrollToItem(0)
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) { Icon(AppIcons.ArrowDown, "最新へ") }
                    }
                }
            }
            if (joined) {
                Box(Modifier.navigationBarsPadding()) {
                    Composer(channelId, replyTo, { replyTo = null }, editing, { editing = null })
                }
            } else if (channel != null) {
                Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("チャンネルプレビュー中", Modifier.weight(1f))
                        Button(onClick = { scope.launch { runCatching { graph.api.joinChannel(channelId) }.onFailure { ctx.toast(it.userMessage()) } } }) { Text("参加する") }
                    }
                }
            }
        }
    }

    // ---- メッセージの操作 (長押し) ----
    actionsFor?.let { m ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            val u = rememberUser(m.userId)
            Column(Modifier.exposeTestTags().testTag(TestTags.MESSAGE_ACTIONS).padding(bottom = 24.dp)) {
                Text("${u?.name ?: ""} ・ ${fullTime(m.createdAt)}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // よく使うリアクション
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val quick = remember { (RecentEmoji.get(ctx) + listOf("thumbsup", "heart", "joy", "eyes", "pray", "tada")).distinct().take(6) }
                    for (code in quick) Box(Modifier.size(44.dp).clickable { cb.onToggleReaction(m, code); RecentEmoji.push(ctx, code); actionsFor = null }, contentAlignment = Alignment.Center) { EmojiView(code, 28.dp) }
                    Box(Modifier.size(44.dp).testTag(TestTags.MESSAGE_ACTIONS_EMOJI_PICKER).clickable { pickerFor = m; actionsFor = null }, contentAlignment = Alignment.Center) { Icon(AppIcons.Mood, "絵文字を選ぶ") }
                }
                HorizontalDivider()
                if (joined) Action(AppIcons.Reply, "返信", tag = TestTags.MESSAGE_ACTIONS_REPLY) { replyTo = m; editing = null; actionsFor = null }
                Action(AppIcons.Link, "リンクをコピー") {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("link", "&<${m.channelId}:${m.id}>"))) }
                    ctx.toast("リンクをコピーしました"); actionsFor = null
                }
                Action(AppIcons.Copy, "本文をコピー") {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", store.plainText(m.content)))) }
                    actionsFor = null
                }
                if (m.userId == me?.id) Action(Icons.Default.Edit, "編集") { editing = m; replyTo = null; actionsFor = null }
                if (m.userId == me?.id || store.can("manageUser")) Action(Icons.Default.Delete, "削除", danger = true) { confirmDelete = m; actionsFor = null }
            }
        }
    }
    pickerFor?.let { m ->
        ModalBottomSheet(onDismissRequest = { pickerFor = null }) {
            EmojiPicker(Modifier.exposeTestTags()) { code ->
                val mine = m.reactionSummary.count { it.includingYou }
                val already = m.reactionSummary.any { it.emojiCode == code && it.includingYou }
                if (!already && mine >= 10) ctx.toast("同一ユーザーによるリアクションは10個までです。")
                else cb.onToggleReaction(m, code)
                pickerFor = null
            }
        }
    }
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("メッセージを削除しますか？") },
            text = { Text(store.plainText(m.content), maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { runCatching { graph.api.delete(m.id) }.onFailure { ctx.toast(it.userMessage()) } }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("キャンセル") } },
        )
    }
    whoReacted?.let { (m, code) -> WhoReactedDialog(m, code, onUser = { userSheet = it; whoReacted = null }) { whoReacted = null } }
    imageUrl?.let { ImageViewer(it) { imageUrl = null } }
    expanded?.let { m ->
        Dialog(onDismissRequest = { expanded = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().exposeTestTags().testTag(TestTags.LONG_TEXT_DIALOG).padding(12.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Avatar(m.userId, 32.dp)
                        Text(rememberUser(m.userId)?.name ?: "", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { expanded = null }) { Text("閉じる") }
                    }
                    Text(fullTime(m.createdAt), style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    MessageText(m.content, me?.id, cb)
                }
            }
        }
    }
    userSheet?.let { UserSheet(it) { userSheet = null } }
}

@Composable
private fun Loader() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    val c = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = c) },
        leadingContent = { Icon(icon, null, tint = c) },
        modifier = Modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier).clickable(onClick = onClick),
    )
}

@Composable
private fun WhoReactedDialog(m: Message, code: String, onUser: (String) -> Unit, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    var page by remember { mutableStateOf(1) }
    val ids by produceState<List<String>?>(null, page) {
        val got = runCatching { graph.api.whoReacted(m.id, code, page) }.getOrDefault(emptyList())
        value = (value ?: emptyList()) + got
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { EmojiView(code, 24.dp); Text(code, style = MaterialTheme.typography.titleSmall) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (ids == null) CircularProgressIndicator()
                ids?.distinct()?.forEach { id ->
                    val u = rememberUser(id)
                    Row(Modifier.fillMaxWidth().clickable { onUser(id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Avatar(id, 28.dp); Text(u?.name ?: "…")
                    }
                }
                val total = m.reactionSummary.firstOrNull { it.emojiCode == code }?.count ?: 0
                if ((ids?.size ?: 0) in 1 until total) TextButton(onClick = { page++ }) { Text("もっと見る") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** 画像の全画面表示 (ピンチで拡大) */
@Composable
fun ImageViewer(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                    }
                },
        ) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
        }
    }
}

/** 添付ファイルを端末の「ダウンロード」へ保存する。認証 Cookie を付けて DownloadManager に任せる */
fun download(ctx: Context, url: String, f: FileAttached, token: String?) {
    try {
        val req = DownloadManager.Request(url.toUri())
            .setTitle(f.actualFileName)
            .setMimeType(f.type)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, f.actualFileName.replace('/', '_'))
        if (token != null) req.addRequestHeader("Cookie", "token=$token")
        ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        ctx.toast("ダウンロードを開始しました")
    } catch (e: Exception) {
        ctx.toast("ダウンロードできません: ${e.message}")
    }
}
