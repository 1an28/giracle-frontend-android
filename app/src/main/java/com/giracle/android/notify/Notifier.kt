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
import androidx.core.content.ContextCompat
import com.giracle.android.AppGraph
import com.giracle.android.MainActivity
import com.giracle.android.R
import com.giracle.android.data.InAppNotice
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android の通知。
 * WebSocket で受け取ったメンション・返信・(設定次第で) 全新着を、アプリが背面にあるときだけ通知にする。
 * 前面にあるときは画面内のバナーで知らせる (Root.kt)。
 *
 * サーバーからのプッシュ (FCM) ではないため、アプリのプロセスが生きている間だけ届く。
 * 常に受け取りたい場合は設定の「バックグラウンドでも接続を保つ」で [ConnectionService] を動かす。
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

    fun post(context: Context, n: InAppNotice, tag: String? = null) {
        if (!canPost(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CHANNEL, n.channelId)
            putExtra(MainActivity.EXTRA_MESSAGE, n.messageId)
        }
        val id = nextId++
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setGroup(n.channelId ?: "giracle")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            // 同じメッセージの通知 (WS とプッシュの両方から来た場合など) は tag で 1 つにまとめる
            NotificationManagerCompat.from(context).notify(tag ?: n.messageId ?: id.toString(), 0, notif)
        } catch (_: SecurityException) {
        }
    }
}
