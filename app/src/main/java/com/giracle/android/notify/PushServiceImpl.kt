package com.giracle.android.notify

import android.os.Build
import android.util.Log
import com.giracle.android.GiracleApp
import com.giracle.android.data.InAppNotice
import com.giracle.android.data.model.PushPayload
import com.giracle.android.data.net.AppJson
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush でプッシュ通知を受け取るサービス。
 *
 * Giracle のサーバーは Web Push (VAPID + RFC 8291 暗号化) で通知を送る。UnifiedPush の宛先は
 * Web Push と同じ形式なので、この端末を platform="web" の端末として登録すれば、
 * サーバー側のコードを変えずに Android へ通知が届く。
 * 端末には配信アプリ (例: ntfy) が必要。暗号の解読はライブラリが行う。
 */
class PushServiceImpl : PushService() {
    private val graph get() = (application as GiracleApp).graph

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "new endpoint: ${endpoint.url.substringBefore("/fcm/send/").take(60)}")
        val keys = endpoint.pubKeySet ?: run {
            Log.w(TAG, "endpoint without keys")
            return
        }
        val g = graph
        g.scope.launch {
            runCatching {
                g.prefs.pushEndpoint?.takeIf { it != endpoint.url }?.let { old -> g.api.deviceUnregister(old) }
                g.api.deviceRegister(endpoint.url, keys.pubKey, keys.auth, "Android ${Build.MODEL} (Giracle Native)")
                g.prefs.pushEndpoint = endpoint.url
            }.onFailure { Log.w(TAG, "register failed", it) }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        Log.i(TAG, "message: decrypted=${message.decrypted} bytes=${message.content.size}")
        if (!message.decrypted) return
        val p = runCatching { AppJson.decodeFromString(PushPayload.serializer(), message.content.decodeToString()) }
            .onFailure { Log.w(TAG, "payload decode failed", it) }.getOrNull() ?: return
        // 前面にいてリアルタイム接続があるなら、画面内の通知で足りる
        if (graph.store.appInForeground && graph.ws.state.value == com.giracle.android.data.net.WsClient.State.Connected) {
            Log.i(TAG, "skip: in foreground")
            return
        }
        Log.i(TAG, "post notification (canPost=${Notifier.canPost(this)})")
        // 題は「<名前> さんからのメンション」の形 (giracle-backend の SendMessageNotifications.ts)。名前を取り出して会話の形にする
        val sender = SENDER.find(p.title)?.groupValues?.get(1)
        Notifier.post(this, InAppNotice(p.title, graph.store.plainText(p.body), p.data?.channelId, p.data?.messageId, sender = sender), tag = p.data?.messageId ?: p.tag)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        val g = graph
        val ep = g.prefs.pushEndpoint ?: return
        g.scope.launch {
            runCatching { g.api.deviceUnregister(ep) }
            g.prefs.pushEndpoint = null
        }
    }

    private companion object {
        const val TAG = "GiraclePush"
        val SENDER = Regex("^(.+) さんからの")
    }
}
