package com.giracle.android.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.data.model.Message
import com.giracle.android.ui.LocalNavigator
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.SubScreen
import com.giracle.android.ui.common.friendlyTime
import com.giracle.android.ui.common.rememberUser
import com.giracle.android.ui.common.userMessage
import kotlinx.coroutines.launch

/** メッセージ検索 (Web 版 routes/search.tsx)。本文・チャンネル・送信者・URL 有無・添付有無・並び順 */
@Composable
fun SearchScreen(initialChannel: String?, initialUser: String?) {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val channels by store.channels.collectAsStateWithLifecycle()
    var content by remember { mutableStateOf("") }
    var channelId by remember { mutableStateOf(initialChannel) }
    var userId by remember { mutableStateOf(initialUser) }
    var hasUrl by remember { mutableStateOf(false) }
    var hasFile by remember { mutableStateOf(false) }
    var asc by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<Message>() }
    var page by remember { mutableStateOf(1) }
    var end by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var chMenu by remember { mutableStateOf(false) }

    fun run(reset: Boolean) {
        if (busy) return
        busy = true; error = null
        val p = if (reset) 1 else page + 1
        scope.launch {
            try {
                val r = graph.api.search(content, channelId, userId, hasUrl.takeIf { it }, hasFile.takeIf { it }, p, if (asc) "asc" else "desc")
                if (reset) results.clear()
                results.addAll(r)
                page = p
                end = r.isEmpty()
                r.forEach { store.ensureUser(it.userId) }
            } catch (e: Exception) { error = e.userMessage() } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { if (initialChannel != null || initialUser != null) run(true) }

    SubScreen("検索") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(content, { content = it }, placeholder = { Text("キーワード") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run(true) }))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column {
                            FilterChip(selected = channelId != null, onClick = { chMenu = true }, label = { Text(channelId?.let { id -> "#" + (channels.firstOrNull { it.id == id }?.name ?: "?") } ?: "全チャンネル") })
                            DropdownMenu(chMenu, { chMenu = false }) {
                                DropdownMenuItem(text = { Text("全チャンネル") }, onClick = { channelId = null; chMenu = false })
                                channels.forEach { c -> DropdownMenuItem(text = { Text("#" + c.name) }, onClick = { channelId = c.id; chMenu = false }) }
                            }
                        }
                        if (userId != null) {
                            val u = rememberUser(userId!!)
                            FilterChip(selected = true, onClick = { userId = null }, label = { Text("送信者: ${u?.name ?: "…"} ✕") })
                        }
                        FilterChip(selected = hasUrl, onClick = { hasUrl = !hasUrl }, label = { Text("URL あり") })
                        FilterChip(selected = hasFile, onClick = { hasFile = !hasFile }, label = { Text("添付あり") })
                        FilterChip(selected = asc, onClick = { asc = !asc }, label = { Text(if (asc) "古い順" else "新しい順") })
                    }
                    Button(onClick = { run(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("検索") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            items(results, key = { it.id }) { m ->
                ResultRow(m, channels.firstOrNull { it.id == m.channelId }?.name) { nav.openChannel(m.channelId, m.id) }
                HorizontalDivider()
            }
            item {
                if (busy) CircularProgressIndicator(Modifier.padding(16.dp))
                else if (results.isNotEmpty() && !end) TextButton(onClick = { run(false) }, modifier = Modifier.fillMaxWidth()) { Text("さらに読み込む") }
                else if (results.isEmpty() && page >= 1 && !end) Unit
            }
        }
    }
}

@Composable
private fun ResultRow(m: Message, channelName: String?, onClick: () -> Unit) {
    val store = LocalStore.current
    val u = rememberUser(m.userId)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Avatar(m.userId, 32.dp)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(u?.name ?: "…", fontWeight = FontWeight.Bold)
                Text("#${channelName ?: "?"} ・ ${friendlyTime(m.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(store.plainText(m.content), maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (m.files.isNotEmpty()) Text("添付 ${m.files.size} 件", style = MaterialTheme.typography.labelSmall)
        }
    }
}
