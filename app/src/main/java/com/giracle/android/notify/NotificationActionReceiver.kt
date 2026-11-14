package com.giracle.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.giracle.android.GiracleApp
import com.giracle.android.data.ChatStore
import com.giracle.android.data.Outbox
import kotlinx.coroutines.launch

/**
 * 通知のボタン (返信・既読にする) を受ける (D-022)。アプリを開かずに、通知から直接操作できる。
 * 返信は、通知のメッセージへの返信 (replyingMessageId 付き) として送る。どちらも、そのメッセージを既読にする。
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL) ?: return
        if (intent.action == ACTION_DISMISSED) {
            Notifier.forgetChannel(channelId)
            return
        }
        val messageId = intent.getStringExtra(EXTRA_MESSAGE)
        val tag = intent.getStringExtra(EXTRA_TAG)
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        val graph = (context.applicationContext as GiracleApp).graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                val store = graph.store
                if (intent.action == ACTION_REPLY && !reply.isNullOrEmpty()) {
                    // 返信した会話の通知は残す (#38)。この後に既読時刻が進んでも、既読による消去 (#49) の対象にしない
                    Notifier.keepAfterReply(channelId)
                    try {
                        store.send(channelId, reply, emptyList(), messageId)
                    } catch (e: Exception) {
                        if (!Outbox.isNetworkFailure(e)) throw e
                        // 電波が無いときは送信待ちに入れ、次につながったときに送る (#30)
                        graph.outbox.add(channelId, reply, emptyList(), messageId, userId = store.me.value?.id ?: graph.prefs.userId)
                        Notifier.postError(context, "返信を送信待ちにしました", tag, "次にアプリがつながったときに送ります")
                        return@launch
                    }
                }
                if (messageId != null) {
                    store.readInbox(messageId)
                    // 既読の位置を、このメッセージまで進める (今より前には戻さない)
                    runCatching {
                        val t = graph.api.message(messageId).createdAt
                        val cur = store.readTimes.value[channelId]
                        if (cur == null || ChatStore.isAfter(t, cur)) {
                            store.readTimes.value = store.readTimes.value + (channelId to t)
                            graph.api.updateReadTime(channelId, t)
                        }
                    }
                }
                // 返信したら、通知を消さずに会話へ自分の発言として足す (続けて返信できるように。#38)。
                // 「既読にする」と、元の通知が分からないときは消す (失敗したときは残して、アプリから操作できるようにする)
                val kept = intent.action == ACTION_REPLY && !reply.isNullOrEmpty() && Notifier.appendOwnReply(context, channelId, reply)
                if (!kept) Notifier.cancelChannel(context, channelId)
            } catch (e: Exception) {
                Log.w(TAG, "notification action failed", e)
                Notifier.postError(context, if (intent.action == ACTION_REPLY) "返信を送れませんでした" else "既読にできませんでした", tag)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GiracleNotifAction"
        const val ACTION_REPLY = "com.giracle.android.action.REPLY"
        const val ACTION_MARK_READ = "com.giracle.android.action.MARK_READ"
        const val ACTION_DISMISSED = "com.giracle.android.action.DISMISSED"
        const val KEY_REPLY = "reply"
        const val EXTRA_CHANNEL = "channelId"
        const val EXTRA_MESSAGE = "messageId"
        const val EXTRA_TAG = "tag"
    }
}
