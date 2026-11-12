package com.giracle.android.ui.channel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.giracle.android.ui.common.TestTags
import com.giracle.android.data.Emojis
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import kotlinx.coroutines.launch

/** 最近使った絵文字 (端末内に保存) */
object RecentEmoji {
    private const val KEY = "recentEmoji"
    fun get(ctx: android.content.Context): List<String> =
        ctx.getSharedPreferences("giracle", 0).getString(KEY, "")!!.split(' ').filter { it.isNotEmpty() }

    fun push(ctx: android.content.Context, code: String) {
        val l = (listOf(code) + get(ctx).filterNot { it == code }).take(24)
        ctx.getSharedPreferences("giracle", 0).edit().putString(KEY, l.joinToString(" ")).apply()
    }
}

private sealed interface PickItem {
    data class Header(val title: String) : PickItem
    data class Uni(val code: String, val char: String) : PickItem
    data class Custom(val code: String) : PickItem
}

/**
 * 絵文字ピッカー。Web 版 (emoji-picker-element) と同じショートコードを返すので、
 * Web 版で付けたリアクションと同じものとして数えられる。
 */
@Composable
fun EmojiPicker(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val graph = LocalGraph.current
    val store = LocalStore.current
    val customs by store.customEmoji.collectAsStateWithLifecycle()
    val data by produceState(Emojis.now()) { if (value == null) value = Emojis.await() }
    var query by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val recent = remember { RecentEmoji.get(ctx) }

    val items: List<PickItem> = remember(data, customs, query) {
        val out = ArrayList<PickItem>()
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            customs.filter { it.code.lowercase().contains(q) }.forEach { out += PickItem.Custom(it.code) }
            Emojis.search(q).forEach { out += PickItem.Uni(it.code, it.char) }
            return@remember out
        }
        if (recent.isNotEmpty()) {
            out += PickItem.Header("最近使った絵文字")
            for (c in recent) {
                val u = Emojis.unicodeOf(c)
                out += if (u != null) PickItem.Uni(c, u) else PickItem.Custom(c)
            }
        }
        if (customs.isNotEmpty()) {
            out += PickItem.Header("カスタム絵文字")
            customs.forEach { out += PickItem.Custom(it.code) }
        }
        data?.all?.groupBy { it.group }?.toSortedMap()?.forEach { (g, list) ->
            out += PickItem.Header(Emojis.groupNames.getOrElse(g) { "その他" })
            list.forEach { out += PickItem.Uni(it.code, it.char) }
        }
        out
    }
    val headers = remember(items) { items.mapIndexedNotNull { i, it -> (it as? PickItem.Header)?.let { h -> h.title to i } } }

    fun pick(code: String) {
        RecentEmoji.push(ctx, code)
        onPick(code)
    }

    Column(modifier.fillMaxWidth().height(440.dp).testTag(TestTags.EMOJI_PICKER)) {
        OutlinedTextField(query, { query = it }, placeholder = { Text("絵文字を検索 (日本語・英語)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(TestTags.EMOJI_PICKER_SEARCH))
        if (query.isBlank() && headers.size > 1) {
            PrimaryScrollableTabRow(selectedTabIndex = 0, edgePadding = 8.dp, divider = {}) {
                headers.forEach { (title, idx) ->
                    Tab(selected = false, onClick = { scope.launch { gridState.scrollToItem(idx) } }, text = { Text(title, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(44.dp), state = gridState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(items, span = { if (it is PickItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }, contentType = { it::class }) { it ->
                when (it) {
                    is PickItem.Header -> Text(it.title, Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is PickItem.Uni -> Box(Modifier.size(44.dp).testTag(TestTags.emojiPickerItem(it.code)).clickable { pick(it.code) }, contentAlignment = Alignment.Center) { Text(it.char, fontSize = 26.sp) }
                    is PickItem.Custom -> Box(Modifier.size(44.dp).testTag(TestTags.emojiPickerItem(it.code)).clickable { pick(it.code) }, contentAlignment = Alignment.Center) {
                        AsyncImage(model = graph.api.customEmojiUrl(it.code), contentDescription = it.code, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}
