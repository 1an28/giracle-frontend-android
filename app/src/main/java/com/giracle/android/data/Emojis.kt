package com.giracle.android.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Immutable
data class EmojiEntry(val group: Int, val char: String, val shortcodes: List<String>, val words: String) {
    /** リアクションで送るコード。Web 版 (emoji-picker-element) は shortcodes[0] を送る */
    val code: String get() = shortcodes.first()
}

/**
 * Unicode 絵文字のデータ。emoji-picker-element-data (emojibase) から作った assets/emoji.tsv を読む。
 * Web 版のリアクションは「ショートコード」(例: thumbsup) で保存されるので、その対応表になる。
 * 起動を遅らせないよう、最初に必要になったときバックグラウンドで読む。
 */
object Emojis {
    private val ready = CompletableDeferred<Data>()
    @Volatile private var data: Data? = null

    class Data(val all: List<EmojiEntry>, val byCode: Map<String, EmojiEntry>)

    val groupNames = listOf("顔", "人", "肌", "動物", "食べ物", "旅行", "活動", "物", "記号", "旗")

    fun preload(context: Context, scope: CoroutineScope) {
        if (data != null || ready.isCompleted) return
        scope.launch(Dispatchers.IO) {
            val list = ArrayList<EmojiEntry>(2000)
            val map = HashMap<String, EmojiEntry>(4000)
            context.assets.open("emoji.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size < 3) continue
                    val e = EmojiEntry(p[0].toIntOrNull() ?: 0, p[1], p[2].split(','), p.getOrElse(3) { "" })
                    list += e
                    for (c in e.shortcodes) map.putIfAbsent(c, e)
                }
            }
            val d = Data(list, map)
            data = d
            ready.complete(d)
        }
    }

    /** 読み込み済みなら即座に返す (未読み込みなら null) */
    fun now(): Data? = data

    suspend fun await(): Data = ready.await()

    /** ショートコード → 絵文字文字。未知なら null (カスタム絵文字の可能性) */
    fun unicodeOf(code: String): String? = data?.byCode?.get(code)?.char

    fun search(query: String, limit: Int = 200): List<EmojiEntry> {
        val d = data ?: return emptyList()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return d.all
        return d.all.asSequence().filter { e -> e.shortcodes.any { it.contains(q) } || e.words.contains(q) }.take(limit).toList()
    }
}
