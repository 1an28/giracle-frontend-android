package com.giracle.android.notify

import android.app.Activity
import android.content.Context
import android.util.Log
import com.giracle.android.AppGraph
import com.giracle.android.data.net.ApiException
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor

/**
 * プッシュ通知の登録・解除をまとめたもの (D-019)。
 *
 * 受け取りは UnifiedPush。配信アプリ (ntfy など) が入っていればそれを使い、無ければアプリに組み込んだ
 * 配信 (embedded FCM distributor) が Google Play 開発者サービス経由で受け取る。どちらでも宛先は Web Push と同じ形式なので、
 * サーバーには platform="web" の端末として登録し、サーバーの既存の Web Push (VAPID) がそのまま届く。
 * 組み込みの配信は Firebase プロジェクトも google-services.json も使わない (VAPID 公開鍵を送信者として登録する)。
 *
 * 登録が済むと [PushServiceImpl.onNewEndpoint] が呼ばれ、そこでサーバーに端末を登録する。
 */
object PushRegistrar {
    private const val TAG = "GiraclePush"

    sealed interface Result {
        /** 配信先に登録を頼んだ。数秒後に [PushServiceImpl.onNewEndpoint] で有効になる */
        data object Requested : Result
        /** 使える配信先が無い (Google Play 開発者サービスも配信アプリも無い) */
        data object NoDistributor : Result
        /** サーバーに VAPID 鍵が設定されていない */
        data object ServerNotConfigured : Result
        data class Failed(val error: Throwable) : Result
    }

    /** 自動で有効にするのを試したユーザー (同じプロセスで同じユーザーに何度も試さない) */
    @Volatile private var autoTriedFor: String? = null

    /**
     * ログイン後に自動で有効にする。利用者が無効にしていない・未登録・配信先が 1 つに決まる、の 3 つを満たすときだけ。
     * 配信先を選ぶ画面が要る場合 (配信アプリが複数あるなど) は何もしない (設定画面から選んでもらう)。
     */
    suspend fun autoEnable(context: Context, graph: AppGraph, userId: String) {
        val prefs = graph.prefs
        if (prefs.pushOptOut || prefs.pushEndpoint != null) return
        // 通知の権限がまだなら、許可された後にもう一度呼ばれる
        if (!Notifier.canPost(context)) return
        if (autoTriedFor == userId) return
        autoTriedFor = userId
        val resolved = runCatching { UnifiedPush.getAckDistributor(context) }.getOrNull()
            ?: when (val r = runCatching { UnifiedPush.resolveDefaultDistributor(context) }.getOrNull()) {
                is ResolvedDistributor.Found -> r.packageName
                else -> return
            }
        val key = try {
            graph.api.vapidPublicKey()
        } catch (e: Exception) {
            Log.i(TAG, "auto enable skipped: ${e.message}")
            return
        }
        UnifiedPush.saveDistributor(context, resolved)
        UnifiedPush.register(context, vapid = key)
        Log.i(TAG, "auto enable requested ($resolved)")
    }

    /** 設定画面の「有効にする」。配信アプリが複数あれば、OS の選択画面が出ることがある */
    suspend fun enable(activity: Activity, graph: AppGraph, onResult: (Result) -> Unit) {
        val key = try {
            graph.api.vapidPublicKey()
        } catch (e: ApiException) {
            onResult(if (e.code == 503) Result.ServerNotConfigured else Result.Failed(e))
            return
        } catch (e: Exception) {
            onResult(Result.Failed(e))
            return
        }
        graph.prefs.pushOptOut = false
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) {
                UnifiedPush.register(activity, vapid = key)
                onResult(Result.Requested)
            } else {
                onResult(Result.NoDistributor)
            }
        }
    }

    /** 設定画面の「無効にする」。以後、自動では有効にしない */
    fun disable(context: Context, graph: AppGraph) {
        graph.prefs.pushOptOut = true
        UnifiedPush.unregister(context)
    }

    /**
     * ログアウトの前に呼ぶ。サーバーから端末の登録を消し (ログアウト後は消せない)、配信先の登録も解く。
     * 次にログインしたときは、また自動で有効にする。
     */
    suspend fun onSignOut(context: Context, graph: AppGraph) {
        val ep = graph.prefs.pushEndpoint
        if (ep != null) runCatching { graph.api.deviceUnregister(ep) }
        graph.prefs.pushEndpoint = null
        runCatching { UnifiedPush.unregister(context) }
        autoTriedFor = null
    }

    /** 設定画面に出す、今の状態の説明 */
    fun status(context: Context, endpoint: String?): String {
        val dists = runCatching { UnifiedPush.getDistributors(context) }.getOrDefault(emptyList())
        return when {
            endpoint != null -> "有効 (" + distributorLabel(context, UnifiedPush.getAckDistributor(context)) + ")"
            dists.isEmpty() -> "使えません: Google Play 開発者サービスも、UnifiedPush の配信アプリ (ntfy など) もありません。" +
                "「バックグラウンドでも接続を保つ」を使ってください"
            else -> "無効"
        }
    }

    /** 組み込みの配信 (自分自身) なら「Google 経由」、外部の配信アプリならその名前 */
    fun distributorLabel(context: Context, pkg: String?): String = when (pkg) {
        null -> "配信先: 不明"
        context.packageName -> "Google Play 開発者サービス経由"
        else -> "配信アプリ: " + runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
