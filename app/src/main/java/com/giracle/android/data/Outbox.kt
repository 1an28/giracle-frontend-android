package com.giracle.android.data

import android.util.Log
import androidx.compose.runtime.Immutable
import com.giracle.android.data.model.Message
import com.giracle.android.data.net.ApiException
import com.giracle.android.data.net.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** 電波が無くて送れなかったメッセージ (#30)。つながったら送り直す */
@Immutable
@Serializable
data class OutboxItem(
    val localId: String,
    val channelId: String,
    val text: String,
    val fileIds: List<String> = emptyList(),
    val replyTo: String? = null,
    /** 最初に送ろうとした時刻 (エポックミリ秒) */
    val createdAt: Long,
    /** サーバーに断られたときの理由。これがあるものは自動では送り直さない */
    val error: String? = null,
)

/**
 * 送信待ちの箱 (#30、D-029)。アプリを閉じても残るよう、端末のファイルに保存する。
 * - ネットワークの失敗で送れなかったものだけを入れる (サーバーに断られたものは入れない)
 * - つながったら (WebSocket の接続) 古い順に送る。途中でまたネットワークで失敗したら、そこで止める
 * - サーバーには送信の重複を防ぐ仕組みが無い (S-8) ので、送り直す前に最近の履歴を見て、
 *   同じものがその後に自分から送られていれば「届いていた」とみなして送らない ([findDelivered]、#66)
 * - 保存は [SafeFile] で 1 本にまとめ、メインスレッドの外で書く (#67)。書くのは書く時点の最新の中身
 */
class Outbox(file: File) {
    private val file = SafeFile(file)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<OutboxItem>> = _items
    private val mutex = Mutex()
    private val usedFile = SafeFile(File(file.path + ".used"))
    /**
     * 送信待ちの箱が「届いていた」とみなした・送ったメッセージの ID (新しいものが後)。
     * 次の送り直しで、同じメッセージをほかの項目に当てないために、送り直しをまたいで (ファイルにも) 残す (#66)
     */
    private val used: LinkedHashSet<String> = loadUsed()

    /** 送る処理 (差し替えはテスト用) */
    interface Sender {
        /** @return 送ったメッセージの ID */
        suspend fun send(item: OutboxItem): String
        /** そのチャンネルの最近のメッセージ (新しい順でなくてよい) */
        suspend fun recent(channelId: String): List<Message>
        val myUserId: String?
    }

    suspend fun add(channelId: String, text: String, fileIds: List<String>, replyTo: String?, now: Long = System.currentTimeMillis()): OutboxItem {
        val item = OutboxItem(UUID.randomUUID().toString(), channelId, text, fileIds, replyTo, now)
        _items.update { it + item }
        save()
        return item
    }

    suspend fun remove(localId: String) {
        _items.update { l -> l.filterNot { it.localId == localId } }
        save()
    }

    /** ログアウトのときに呼ぶ。後から走る保存は空の一覧を書くので、前の内容は戻らない */
    fun clear() {
        _items.value = emptyList()
        file.delete()
        used.clear()
        usedFile.delete()
    }

    /**
     * 送信待ちを古い順に送る。[only] を指定したときはそれだけを送る (断られたものも含めて、利用者が「今すぐ送る」を選んだとき)。
     * @return 送れた (または届いていたと分かった) 件数
     */
    suspend fun flush(sender: Sender, only: String? = null): Int = mutex.withLock {
        var done = 0
        val targets = _items.value.filter { if (only != null) it.localId == only else it.error == null }
        val recentByChannel = HashMap<String, List<Message>>()
        for (item in targets) {
            try {
                val recent = recentByChannel.getOrPut(item.channelId) { sender.recent(item.channelId) }
                // 一致に使ったもの・送ったものは、ほかの項目に当てない (#66)
                val delivered = findDelivered(item, recent, sender.myUserId, used)
                if (delivered != null) {
                    Log.i(TAG, "already delivered: ${item.localId} as ${delivered.id}")
                    markUsed(delivered.id)
                } else {
                    markUsed(sender.send(item))
                    Log.i(TAG, "sent: ${item.localId}")
                }
                remove(item.localId)
                done++
            } catch (e: ApiException) {
                if (e.isUnauthorized) { Log.i(TAG, "unauthorized: ${item.localId}"); break }
                // サーバーに断られた (削除されたチャンネルなど)。自動では送り直さず、利用者に選んでもらう
                Log.i(TAG, "rejected: ${item.localId} HTTP ${e.code}")
                _items.update { l -> l.map { if (it.localId == item.localId) it.copy(error = e.body.take(200).ifBlank { "HTTP ${e.code}" }) else it } }
                save()
            } catch (e: IOException) {
                // まだつながらない。後の分も送れないので止める
                Log.i(TAG, "still offline: $e")
                break
            }
        }
        done
    }

    private fun load(): List<OutboxItem> = runCatching {
        file.readText()?.let { AppJson.decodeFromString(ListSerializer(OutboxItem.serializer()), it) } ?: emptyList()
    }.onFailure { Log.w(TAG, "load failed", it) }.getOrDefault(emptyList())

    private fun loadUsed(): LinkedHashSet<String> = runCatching {
        usedFile.readText()?.let { LinkedHashSet(AppJson.decodeFromString(ListSerializer(String.serializer()), it)) } ?: LinkedHashSet()
    }.onFailure { Log.w(TAG, "load used failed", it) }.getOrDefault(LinkedHashSet())

    /** [flush] の中 (mutex の内側) からだけ呼ぶ */
    private suspend fun markUsed(messageId: String) {
        used.remove(messageId)
        used += messageId
        // 比べる相手は最近 30 件なので、古いものは捨ててよい
        while (used.size > MAX_USED) used.remove(used.first())
        val snapshot = used.toList()
        withContext(Dispatchers.IO) {
            try {
                usedFile.write { AppJson.encodeToString(ListSerializer(String.serializer()), snapshot) }
            } catch (e: IOException) {
                Log.w(TAG, "save used failed", e)
            }
        }
    }

    /** 今の一覧を保存する。同時に呼ばれても [SafeFile] が順に書き、書く直前の最新の一覧を書く */
    private suspend fun save() = withContext(Dispatchers.IO) {
        try {
            file.write { AppJson.encodeToString(ListSerializer(OutboxItem.serializer()), _items.value) }
        } catch (e: IOException) {
            Log.w(TAG, "save failed", e)
        }
    }

    companion object {
        private const val TAG = "Outbox"
        /** 端末とサーバーの時計のずれを見込む幅 */
        private const val CLOCK_SLACK_MS = 2 * 60 * 1000L
        private const val MAX_USED = 100

        /**
         * 送ったが応答が返らなかった場合に、すでに届いているか。届いていれば、そのメッセージを返す (#66)。
         * - 送信者と返信先が一致し、[used] (ほかの項目の一致に使ったもの) に入っていないこと
         * - 添付があるものは、添付の ID の組が一致すること。アップロードの ID は一意で、サーバーは添付済みのファイルの再利用を断るので、
         *   これで確実に判定できる (時刻は見ない)
         * - 添付が無いものは、添付の無いメッセージで本文が一致し、「最初に送ろうとした時刻 - 2 分」以降に送られていること。
         *   同じ本文 (「了解」など) を続けて送った場合の取り違えは、送信の重複を防ぐ ID (S-8) が無い限り残る
         */
        fun findDelivered(item: OutboxItem, recent: List<Message>, myUserId: String?, used: Set<String> = emptySet()): Message? {
            val fileIds = item.fileIds.toSet()
            return recent.firstOrNull { m ->
                m.id !in used && m.userId == myUserId && m.replyingMessageId == item.replyTo &&
                    if (fileIds.isNotEmpty()) {
                        m.files.mapTo(HashSet()) { it.id } == fileIds
                    } else {
                        m.files.isEmpty() && m.content == item.text &&
                            (runCatching { Instant.parse(m.createdAt).toEpochMilli() }.getOrNull() ?: 0L) >= item.createdAt - CLOCK_SLACK_MS
                    }
            }
        }

        /** 送信の失敗が、送信待ちに入れるべきもの (ネットワークの失敗) か。サーバーに断られたものは入れない */
        fun isNetworkFailure(e: Throwable): Boolean = e is IOException && e !is ApiException
    }
}
