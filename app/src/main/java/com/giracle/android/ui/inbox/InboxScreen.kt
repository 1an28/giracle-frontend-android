package com.giracle.android.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.data.model.InboxItem
import com.giracle.android.ui.LocalNavigator
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.Badge
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.SubScreen
import com.giracle.android.ui.common.dayLabel
import com.giracle.android.ui.common.friendlyTime
import com.giracle.android.ui.common.rememberUser
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.userMessage
import kotlinx.coroutines.launch

/** 通知 (メンション・返信) 一覧。Web 版 Inbox と同じく日付別/チャンネル別で見られる */
@Composable
fun InboxScreen() {
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val inbox by store.inbox.collectAsStateWithLifecycle()
    val channels by store.channels.collectAsStateWithLifecycle()
    var byChannel by rememberSaveable { mutableStateOf(false) }

    val groups: List<Pair<String, List<InboxItem>>> = remember(inbox, byChannel, channels) {
        val sorted = inbox.sortedByDescending { it.message.createdAt }
        if (byChannel) sorted.groupBy { it.message.channelId }.map { (id, l) -> ("#" + (channels.firstOrNull { it.id == id }?.name ?: "不明なチャンネル")) to l }
        else sorted.groupBy { dayLabel(it.message.createdAt) }.toList()
    }

    SubScreen("通知", actions = {
        if (inbox.isNotEmpty()) TextButton(onClick = { scope.launch { runCatching { store.clearInbox() }.onFailure { ctx.toast(it.userMessage()) } } }) { Text("すべて既読") }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !byChannel, onClick = { byChannel = false }, label = { Text("日付別") })
                FilterChip(selected = byChannel, onClick = { byChannel = true }, label = { Text("チャンネル別") })
            }
            if (inbox.isEmpty()) Text("新しい通知はありません", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                for ((title, list) in groups) {
                    item(key = "h:$title") { Text(title, Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(list, key = { it.message.id }) { item ->
                        InboxRow(
                            item,
                            onOpen = { nav.openChannel(item.message.channelId, item.message.id) },
                            onRead = { scope.launch { store.readInbox(item.message.id) } },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(item: InboxItem, onOpen: () -> Unit, onRead: () -> Unit) {
    val store = LocalStore.current
    val m = item.message
    val u = rememberUser(m.userId)
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(m.userId, 36.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(u?.name ?: "…", fontWeight = FontWeight.Bold, maxLines = 1)
                Badge(if (item.type == "reply") "返信" else "メンション", strong = true)
                Text(friendlyTime(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(store.plainText(m.content), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = onRead) { Icon(Icons.Default.Check, "既読にする") }
    }
}
