package com.giracle.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.giracle.android.AppGraph
import com.giracle.android.GiracleApp
import com.giracle.android.MainActivity
import com.giracle.android.R
import com.giracle.android.data.InAppNotice
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android の通知。
 * - WebSocket で受け取ったメンション・返信・(設定次第で) 全新着を、アプリが背面にあるときだけ通知にする
 *   (前面にあるときは画面内のバナーで知らせる。Root.kt)
 * - アプリを閉じているときは、プッシュ通知 ([PushServiceImpl]、D-019) から呼ばれる
 * 通知には「返信」「既読にする」のボタンを付ける ([NotificationActionReceiver]、D-022)。
 */
object Notifier {
    const val CH_MESSAGES = "messages"
    const val CH_CONNECTION = "connection"
    private var nextId = 1000

    fun init(context: Context, graph: AppGraph) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "メッセージ", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "メンション・返信・新着メッセージ"
        })
        nm.createNotificationChannel(NotificationChannel(CH_CONNECTION, "接続の維持", NotificationManager.IMPORTANCE_MIN).apply {
            description = "バックグラウンドで接続を保っている間に表示されます"
            setShowBadge(false)
        })
        graph.scope.launch {
            graph.storeFlow.collectLatest { store ->
                store.notices.collect { n -> if (!store.appInForeground) post(context, n) }
            }
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** チャンネルごとの、通知に出している最近のメッセージ (会話の形でまとめる。D-022) */
    private class Line(val messageId: String?, val sender: String, val text: String, val time: Long)
    private val conversations = HashMap<String, ArrayDeque<Line>>()
    private const val MAX_LINES = 8

    private fun channelTag(channelId: String) = "ch:$channelId"

    fun post(context: Context, n: InAppNotice, tag: String? = null) {
        if (!canPost(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CHANNEL, n.channelId)
            putExtra(MainActivity.EXTRA_MESSAGE, n.messageId)
        }
        val id = nextId++
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ch = n.channelId
        val notifTag: String
        val builder = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (ch == null) {
            notifTag = tag ?: n.messageId ?: id.toString()
            builder.setContentTitle(n.title).setContentText(n.body).setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
        } else {
            // チャンネルごとに 1 つの通知にまとめ、会話の形 (MessagingStyle) で最近のメッセージを並べる。
            // 同じメッセージ (WebSocket とプッシュの両方から来た場合など) は 1 回だけ足す
            notifTag = channelTag(ch)
            val lines = synchronized(conversations) {
                val q = conversations.getOrPut(ch) { ArrayDeque() }
                if (n.messageId != null && q.any { it.messageId == n.messageId }) return
                q.addLast(Line(n.messageId, n.sender ?: n.title, n.body, System.currentTimeMillis()))
                while (q.size > MAX_LINES) q.removeFirst()
                q.toList()
            }
            val channelName = runCatching {
                (context.applicationContext as GiracleApp).graph.store.channels.value.firstOrNull { it.id == ch }?.name
            }.getOrNull()
            val style = NotificationCompat.MessagingStyle(Person.Builder().setName("自分").build())
                .setConversationTitle(channelName?.let { "#$it" })
                .setGroupConversation(channelName != null)
            lines.forEach { l -> style.addMessage(l.text, l.time, Person.Builder().setName(l.sender).build()) }
            builder.setStyle(style).setContentTitle(n.title).setContentText(n.body)
            // 通知から直接「返信」「既読にする」 (D-022)。対象は一番新しいメッセージ
            fun action(act: String, req: Int) = PendingIntent.getBroadcast(
                context, req,
                Intent(context, NotificationActionReceiver::class.java).setAction(act)
                    .putExtra(NotificationActionReceiver.EXTRA_CHANNEL, ch)
                    .putExtra(NotificationActionReceiver.EXTRA_MESSAGE, n.messageId)
                    .putExtra(NotificationActionReceiver.EXTRA_TAG, notifTag),
                // 返信は入力した文字を受け取るため、書き換え可能 (MUTABLE) にする必要がある
                PendingIntent.FLAG_UPDATE_CURRENT or (if (act == NotificationActionReceiver.ACTION_REPLY) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
            )
            val input = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("返信").build()
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_message, "返信", action(NotificationActionReceiver.ACTION_REPLY, id * 2))
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build(),
            )
            if (n.messageId != null) builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_message, "既読にする", action(NotificationActionReceiver.ACTION_MARK_READ, id * 2 + 1))
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        try {
            NotificationManagerCompat.from(context).notify(notifTag, 0, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /** チャンネルの通知を消す (そのチャンネルを開いたとき・通知のボタンで操作したとき) */
    fun cancelChannel(context: Context, channelId: String) {
        synchronized(conversations) { conversations.remove(channelId) }
        NotificationManagerCompat.from(context).cancel(channelTag(channelId), 0)
    }

    /** 通知のボタンでの操作が失敗したことを、元の通知を置き換えて知らせる (タップでアプリを開く) */
    fun postError(context: Context, message: String, tag: String?) {
        if (!canPost(context)) return
        val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(message)
            .setContentText("アプリを開いてもう一度試してください")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(tag ?: "giracle-error", 0, notif)
        } catch (_: SecurityException) {
        }
    }
}
