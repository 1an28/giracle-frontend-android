package com.giracle.android.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.data.model.Channel
import com.giracle.android.ui.LocalNavigator
import com.giracle.android.ui.common.AppIcons
import com.giracle.android.ui.common.Badge
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.SubScreen
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.userMessage
import kotlinx.coroutines.launch

/** チャンネル一覧 (Web 版 routes/channel-browser.tsx)。参加・退出・作成・削除 */
@Composable
fun ChannelBrowserScreen() {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels by store.channels.collectAsStateWithLifecycle()
    val me by store.me.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Channel?>(null) }
    val canManage = store.can("manageChannel")
    val joined = me?.channelJoin?.map { it.channelId }?.toSet() ?: emptySet()

    LaunchedEffect(Unit) { runCatching { store.refreshChannels() } }

    fun act(f: suspend () -> Unit) = scope.launch { runCatching { f() }.onFailure { ctx.toast(it.userMessage()) } }

    SubScreen("チャンネル一覧", actions = {
        if (canManage) IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "チャンネルを作成") }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("チャンネル名で絞り込み") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            val shown = channels.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.sortedBy { it.name }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { c ->
                    val isJoined = c.id in joined
                    Row(Modifier.fillMaxWidth().clickable { nav.openChannel(c.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(if (c.viewableRole.isNotEmpty()) Icons.Default.Lock else AppIcons.Tag, null, Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (c.isArchived) Badge("アーカイブ")
                            }
                            if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (isJoined) OutlinedButton(onClick = { act { graph.api.leaveChannel(c.id) } }) { Text("退出") }
                        else Button(onClick = { act { graph.api.joinChannel(c.id) } }) { Text("参加") }
                        if (canManage) IconButton(onClick = { deleting = c }) { Icon(Icons.Default.Delete, "削除", tint = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("チャンネルを作成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true)
                    OutlinedTextField(desc, { desc = it }, label = { Text("説明") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    creating = false
                    act {
                        val r = graph.api.createChannel(name.trim(), desc)
                        store.refreshChannels()
                        graph.api.joinChannel(r.channelId)
                        nav.openChannel(r.channelId)
                    }
                }) { Text("作成") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("キャンセル") } },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("#${c.name} を削除しますか？") },
            text = { Text("メッセージもすべて消え、元に戻せません。") },
            confirmButton = { TextButton(onClick = { deleting = null; act { graph.api.deleteChannel(c.id); store.refreshChannels() } }) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } },
        )
    }
}
