package com.giracle.android.data

import android.util.Log
import androidx.compose.runtime.Immutable
import com.giracle.android.data.model.Message
import com.giracle.android.data.net.ApiException
import com.giracle.android.data.net.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
 */
class Outbox(private val file: File) {
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<OutboxItem>> = _items
    private val mutex = Mutex()

    /** 送る処理 (差し替えはテスト用) */
    interface Sender {
        suspend fun send(item: OutboxItem)
        /** そのチャンネルの最近のメッセージ (新しい順でなくてよい) */
        suspend fun recent(channelId: String): List<Message>
        val myUserId: String?
    }

    fun add(channelId: String, text: String, fileIds: List<String>, replyTo: String?, now: Long = System.currentTimeMillis()): OutboxItem {
        val item = OutboxItem(UUID.randomUUID().toString(), channelId, text, fileIds, replyTo, now)
        _items.update { it + item }
        save()
        return item
    }

    fun remove(localId: String) {
        _items.update { l -> l.filterNot { it.localId == localId } }
        save()
    }

    fun clear() {
        _items.value = emptyList()
        file.delete()
    }

    /**
     * 送信待ちを古い順に送る。[only] を指定したときはそれだけを送る (断られたものも含めて、利用者が「今すぐ送る」を選んだとき)。
     * @return 送れた (または届いていたと分かった) 件数
     */
    suspend fun flush(sender: Sender, only: String? = null): Int = mutex.withLock {
        var done = 0
        val targets = _items.value.filter { if (only != null) it.localId == only else it.error == null }
        val recentByChannel = HashMap<String, List<Message>>()
        // 一致に使ったメッセージ。1 件の届いていたメッセージを、送信待ちの複数の項目に当てない (#66)
        val used = HashSet<String>()
        for (item in targets) {
            try {
                val recent = recentByChannel.getOrPut(item.channelId) { sender.recent(item.channelId) }
                val delivered = findDelivered(item, recent, sender.myUserId, used)
                if (delivered != null) {
                    used += delivered.id
                    Log.i(TAG, "already delivered: ${item.localId} as ${delivered.id}")
                } else {
                    sender.send(item)
                }
                remove(item.localId)
                done++
            } catch (e: ApiException) {
                if (e.isUnauthorized) break
                // サーバーに断られた (削除されたチャンネルなど)。自動では送り直さず、利用者に選んでもらう
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
        if (!file.exists()) emptyList() else AppJson.decodeFromString(ListSerializer(OutboxItem.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun save() {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(AppJson.encodeToString(ListSerializer(OutboxItem.serializer()), _items.value))
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    companion object {
        private const val TAG = "Outbox"
        /** 端末とサーバーの時計のずれを見込む幅 */
        private const val CLOCK_SLACK_MS = 2 * 60 * 1000L

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
