package com.giracle.android.data

import androidx.compose.runtime.Immutable

/**
 * メッセージ本文の記法 (Web 版 MessageTextRender.tsx と同じ):
 * - URL                       : https://...
 * - メンション                : @<userId>
 * - チャンネル                : #<channelId>
 * - メッセージリンク          : &<channelId:messageId>
 * - インラインコード          : `code`
 */
@Immutable
sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Link(val url: String) : Segment
    data class Mention(val userId: String) : Segment
    data class ChannelRef(val channelId: String) : Segment
    data class MessageLink(val channelId: String, val messageId: String) : Segment
    data class Code(val code: String) : Segment
}

object MessageParser {
    private val URL = Regex("""\b(?:https?|ftp|file)://[-A-Z0-9+&@#/%?=~_|!:,.;]*[-A-Z0-9+&@#/%=~_|]""", RegexOption.IGNORE_CASE)
    private val MESSAGE_LINK = Regex("""&<([a-fA-F0-9-]+):([a-fA-F0-9-]+)>""")
    private val MENTION = Regex("""@<([a-zA-Z0-9-]+)>""")
    private val CHANNEL = Regex("""#<([a-fA-F0-9-]+)>""")
    private val CODE = Regex("""`([^`]+)`""")

    // 同じ本文を何度も解析しないよう、小さな LRU に結果を置く (スクロール時の負荷を下げる)
    private val cache = object : LinkedHashMap<String, List<Segment>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Segment>>?) = size > 512
    }

    fun parse(content: String): List<Segment> {
        synchronized(cache) { cache[content]?.let { return it } }
        val result = parseUncached(content)
        synchronized(cache) { cache[content] = result }
        return result
    }

    private class Match(val start: Int, val end: Int, val seg: Segment)

    private fun parseUncached(content: String): List<Segment> {
        if (content.isEmpty()) return emptyList()
        // 記号が 1 つも無ければ解析を省く (大半の短文はここで終わる)
        if (content.none { it == '<' || it == '`' || it == ':' }) return listOf(Segment.Text(content))

        val matches = ArrayList<Match>()
        URL.findAll(content).forEach { matches += Match(it.range.first, it.range.last + 1, Segment.Link(it.value)) }
        MESSAGE_LINK.findAll(content).forEach { matches += Match(it.range.first, it.range.last + 1, Segment.MessageLink(it.groupValues[1], it.groupValues[2])) }
        MENTION.findAll(content).forEach { matches += Match(it.range.first, it.range.last + 1, Segment.Mention(it.groupValues[1])) }
        CHANNEL.findAll(content).forEach { matches += Match(it.range.first, it.range.last + 1, Segment.ChannelRef(it.groupValues[1])) }
        CODE.findAll(content).forEach { matches += Match(it.range.first, it.range.last + 1, Segment.Code(it.groupValues[1])) }
        if (matches.isEmpty()) return listOf(Segment.Text(content))
        // 開始位置順。重なったら先に始まった (同じなら長い) 方を採用
        matches.sortWith(compareBy<Match> { it.start }.thenByDescending { it.end })

        val out = ArrayList<Segment>()
        var pos = 0
        for (m in matches) {
            if (m.start < pos) continue
            if (m.start > pos) out += Segment.Text(content.substring(pos, m.start))
            out += m.seg
            pos = m.end
        }
        if (pos < content.length) out += Segment.Text(content.substring(pos))
        return out
    }

    /** メッセージリンク (&<ch:msg>) を重複なく取り出す。プレビュー表示用 */
    fun messageLinks(content: String): List<Segment.MessageLink> =
        parse(content).filterIsInstance<Segment.MessageLink>().distinct()

    /** 長文判定 (Web 版と同じ: 500 文字超または改行 5 つ超) */
    fun isLong(content: String): Boolean = content.length > 500 || content.count { it == '\n' } > 5

    /** 自分へのメンションを含むか */
    fun mentions(content: String, userId: String): Boolean = content.contains("@<$userId>")
}
