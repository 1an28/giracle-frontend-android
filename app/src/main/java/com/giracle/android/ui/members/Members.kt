package com.giracle.android.ui.members

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.giracle.android.data.model.User
import com.giracle.android.ui.LocalNavigator
import com.giracle.android.ui.Screen
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.Badge
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.RoleChip
import com.giracle.android.ui.common.fullTime
import com.giracle.android.ui.common.rememberUser
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** メンバー一覧 (Web 版 routes/members.tsx)。名前で絞り込み・オンラインのみ表示・無限スクロール */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen() {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val online by store.online.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var onlyOnline by remember { mutableStateOf(false) }
    val users = remember { mutableStateListOf<User>() }
    var end by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        try {
            val cursor = if (reset) null else users.lastOrNull()?.id
            val got = graph.api.userList(length = 50, cursorUserId = cursor, username = query.ifBlank { null })
            got.forEach(store::putUser)
            if (reset) users.clear()
            users.addAll(got.filter { n -> users.none { it.id == n.id } })
            end = got.size < 50
        } catch (_: Exception) {
        } finally { loading = false }
    }
    LaunchedEffect(query) { delay(250); end = false; load(true) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (!end && !loading && last >= users.size - 10 && users.isNotEmpty()) load(false)
        }
    }
    val shown = if (onlyOnline) users.filter { it.id in online } else users

    Scaffold(topBar = {
        TopAppBar(title = { Text("メンバー") }, navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("名前で検索 (前方一致)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = onlyOnline, onClick = { onlyOnline = !onlyOnline }, label = { Text("オンラインのみ (${online.size})") })
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { u -> UserRow(u, u.id in online) { sheet = u.id } }
            }
        }
    }
    sheet?.let { UserSheet(it) { sheet = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserRow(u: User, isOnline: Boolean, trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    val store = LocalStore.current
    val roles by store.roles.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box {
            Avatar(u.id, 40.dp)
            if (isOnline) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Color(0xFF22C55E)))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(u.displayName, fontWeight = FontWeight.Medium)
                if (u.isBot) Badge("BOT")
                if (u.isBanned) Badge("BAN")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                u.roleLink.mapNotNull { roles[it.roleId] }.filter { it.id != "MEMBER" }.forEach { RoleChip(it) }
            }
        }
        trailing?.invoke()
    }
}

/** ユーザー情報 (Web 版 UserinfoModalWrapper)。管理者ならロール付与・BAN もここから */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UserSheet(userId: String, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cached = rememberUser(userId)
    // 最新の情報 (ロール等) を取り直す
    val fresh by produceState(cached, userId) { runCatching { graph.api.userInfo(userId) }.getOrNull()?.let { store.putUser(it); value = it } }
    val u = cached ?: fresh
    val roles by store.roles.collectAsStateWithLifecycle()
    val online by store.online.collectAsStateWithLifecycle()
    val me by store.me.collectAsStateWithLifecycle()
    var roleEdit by remember { mutableStateOf(false) }
    var confirmBan by remember { mutableStateOf(false) }
    val bot by produceState<com.giracle.android.data.model.BotInfo?>(null, u?.isBot) { if (u?.isBot == true) value = runCatching { graph.api.botByRemoteUser(userId) }.getOrNull() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            AsyncImage(
                model = graph.api.bannerUrl(userId), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(110.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(userId, 64.dp)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(u?.displayName ?: "…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (u?.isBot == true) Badge("BOT")
                        if (u?.isBanned == true) Badge("BAN 中", strong = true)
                    }
                    Text(if (userId in online) "オンライン" else "オフライン", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!u?.selfIntroduction.isNullOrBlank()) Text(u.selfIntroduction, Modifier.padding(horizontal = 16.dp))
            bot?.let { b ->
                val creator = rememberUser(b.createdBy)
                Text("作成者: ${creator?.displayName ?: "…"} ・ ${if (b.isApproved) "承認済み" else "未承認"}", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                u?.roleLink?.mapNotNull { roles[it.roleId] }?.forEach { RoleChip(it) }
            }
            u?.createdAt?.let { Text("登録: ${fullTime(it)}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onDismiss(); nav.push(Screen.Search(userId = userId)) }) { Text("投稿を検索") }
                if (store.can("manageRole")) OutlinedButton(onClick = { roleEdit = true }) { Text("ロール") }
                if (store.can("manageUser") && userId != me?.id) {
                    OutlinedButton(onClick = { confirmBan = true }) { Text(if (u?.isBanned == true) "BAN 解除" else "BAN", color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (roleEdit && u != null) {
        AlertDialog(
            onDismissRequest = { roleEdit = false },
            title = { Text("ロールを付与・剥奪") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // HOST / MEMBER / BOT は既定ロールなので操作しない (サーバー側でも拒否される)
                    roles.values.filter { it.id !in setOf("HOST", "MEMBER", "BOT") }.sortedBy { it.name }.forEach { r ->
                        val has = u.roleLink.any { it.roleId == r.id }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = has, onCheckedChange = { on ->
                                scope.launch {
                                    runCatching { if (on) graph.api.linkRole(userId, r.id) else graph.api.unlinkRole(userId, r.id) }
                                        .onFailure { ctx.toast(it.userMessage()) }
                                }
                            })
                            RoleChip(r)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { roleEdit = false }) { Text("閉じる") } },
        )
    }
    if (confirmBan && u != null) {
        AlertDialog(
            onDismissRequest = { confirmBan = false },
            title = { Text(if (u.isBanned) "BAN を解除しますか？" else "${u.displayName} を BAN しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBan = false
                    scope.launch {
                        runCatching { if (u.isBanned) graph.api.unban(userId) else graph.api.ban(userId) }
                            .onSuccess { runCatching { store.putUser(graph.api.userInfo(userId)) } }
                            .onFailure { ctx.toast(it.userMessage()) }
                    }
                }) { Text("実行", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBan = false }) { Text("キャンセル") } },
        )
    }
}
