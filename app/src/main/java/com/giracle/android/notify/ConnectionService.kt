package com.giracle.android.notify

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.giracle.android.GiracleApp
import com.giracle.android.MainActivity
import com.giracle.android.R

/**
 * 「バックグラウンドでも接続を保つ」設定が ON のときだけ動く前面サービス。
 * プロセスを生かして WebSocket をつなぎ続け、メンション等を通知できるようにする。
 * サーバー側のプッシュ通知 (FCM 等) が無くても通知を受けるための手段。電池は多めに使う。
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notifier.CH_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("Giracle に接続中")
            .setContentText("新着をリアルタイムで受け取っています")
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)
        (application as GiracleApp).graph.ws.start()
        return START_STICKY
    }

    companion object {
        fun sync(context: Context, enabled: Boolean) {
            val i = Intent(context, ConnectionService::class.java)
            if (enabled) context.startForegroundService(i) else context.stopService(i)
        }
    }
}
