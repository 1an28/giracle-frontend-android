package com.giracle.android

import android.content.Context
import com.giracle.android.data.ChatStore
import com.giracle.android.data.Prefs
import com.giracle.android.data.model.ServerConfig
import com.giracle.android.data.net.ApiException
import com.giracle.android.data.net.Endpoint
import com.giracle.android.data.net.GiracleApi
import com.giracle.android.data.net.WsClient
import com.giracle.android.data.net.buildOkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/** 画面の大分類 */
sealed interface AppPhase {
    /** 保存済みセッションを確認中 */
    data object Starting : AppPhase
    /** サーバー URL とログインが必要 */
    data object SignedOut : AppPhase
    /** ログイン済み。初期データを読み込み中か完了 */
    data class SignedIn(val userId: String, val ready: Boolean, val error: String? = null) : AppPhase
}

/**
 * 依存関係の入れ物。DI ライブラリは使わず手で組み立てる (起動時間を短くするため)。
 * HTTP・WebSocket・画像読み込みで同じ OkHttpClient を共有する。
 */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val prefs = Prefs(appContext)
    /**
     * アプリ全体の裏の処理。想定していない例外が 1 つの処理から漏れても、アプリごと落とさずに記録だけする
     * (Android では、処理器の無い launch の例外はアプリを落とす)。e2e は logcat に [UNCAUGHT_TAG] が出たら失敗にする
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e(UNCAUGHT_TAG, "uncaught in app scope", e) },
    )

    val http: OkHttpClient = buildOkHttp(prefs, File(appContext.cacheDir, "http"))

    @Volatile var endpoint: Endpoint = Endpoint.parse(prefs.serverUrl ?: DEFAULT_SERVER, prefs.directBackend)
        ?: Endpoint.parse(DEFAULT_SERVER)!!
        private set

    val api = GiracleApi(http, endpoint)
    val ws = WsClient(http, scope) { endpoint }

    /**
     * アプリの画面が前面にあるか (GiracleApp が切り替える)。プッシュで裏から起きたプロセスでは false のまま。
     * 以前は ChatStore の既定値 (true) のままになり、プッシュ通知を「画面内で知らせる」扱いで捨てていた (D-019 の e2e で発見)。
     */
    @Volatile var inForeground = false
        private set

    fun setForeground(fg: Boolean) {
        inForeground = fg
        store.appInForeground = fg
    }

    /** 電波が無くて送れなかったメッセージ (#30、D-029) */
    val outbox = com.giracle.android.data.Outbox(File(appContext.filesDir, "outbox.json"))

    private val outboxSender = object : com.giracle.android.data.Outbox.Sender {
        override suspend fun send(item: com.giracle.android.data.OutboxItem): String =
            store.send(item.channelId, item.text, item.fileIds, item.replyTo).id
        override suspend fun recent(channelId: String) = api.history(channelId, fetchLength = 30).history
        override val myUserId: String? get() = store.me.value?.id ?: prefs.userId
    }

    /** 送信待ちを送る。[only] を指定したらそれだけ (利用者が「今すぐ送る」を選んだとき) */
    fun flushOutbox(only: String? = null) {
        if (_phase.value !is AppPhase.SignedIn || outbox.items.value.isEmpty()) return
        scope.launch { outbox.flush(outboxSender, only) }
    }

    private fun newStore() =
        ChatStore(api, ws, prefs, scope) { inForeground || prefs.settings.value.keepConnection }.also { it.appInForeground = inForeground }

    private val _store = MutableStateFlow(newStore())
    /** ログアウトのたびに作り直す。画面側はこれを購読する */
    val storeFlow: StateFlow<ChatStore> = _store
    val store: ChatStore get() = _store.value

    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
    val phase: StateFlow<AppPhase> = _phase

    private var snapshotJob: kotlinx.coroutines.Job? = null
    @Volatile private var sessionLoadStarted = false

    init {
        val uid = prefs.userId
        if (prefs.hasToken && uid != null && prefs.serverUrl != null) {
            // 保存済みセッションで即座にメイン画面へ (確認は裏で行う)
            _phase.value = AppPhase.SignedIn(uid, ready = false)
            // 前回の内容を先に出す。サーバーの最新への置き換えは、画面を開いたときか常時接続のときに始める
            // (プッシュで裏から起きたときに、通信と接続を始めないため。ensureSessionLoaded)
            snapshotJob = scope.launch { readSnapshot(uid)?.let { store.restore(it) } }
        } else {
            _phase.value = AppPhase.SignedOut
        }
        scope.launch {
            // ログイン状態の移り変わりを記録する (e2e の失敗の手がかり)
            _phase.collect { android.util.Log.i("GiracleNav", "phase $it") }
        }
        scope.launch {
            // つながったら送信待ちを送る (#30)
            ws.state.collect { if (it == com.giracle.android.data.net.WsClient.State.Connected) flushOutbox() }
        }
        scope.launch {
            // 「セッション切れ」通知を監視 (ストアが作り直されたら付け替える)
            _store.collectLatest { s -> s.sessionExpired.collect { if (it) signOutLocal() } }
        }
    }

    private val snapshotFile = java.io.File(appContext.filesDir, "snapshot.json")

    private fun readSnapshot(userId: String): com.giracle.android.data.Snapshot? = runCatching {
        if (!snapshotFile.exists()) return null
        val s = com.giracle.android.data.net.AppJson.decodeFromString(com.giracle.android.data.Snapshot.serializer(), snapshotFile.readText())
        // 別ユーザー・別サーバーのものは使わない
        if (s.userId == userId && snapshotFile.parentFile != null && prefs.serverUrl != null) s else null
    }.getOrNull()

    /** アプリが背面に回ったときに呼ぶ。書き込みは裏で行い、途中で落ちても壊れないよう一時ファイル経由 */
    fun saveSnapshot() {
        val snap = store.snapshot() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = java.io.File(snapshotFile.path + ".tmp")
                tmp.writeText(com.giracle.android.data.net.AppJson.encodeToString(com.giracle.android.data.Snapshot.serializer(), snap))
                tmp.renameTo(snapshotFile)
            }
        }
    }

    fun setServer(url: String, direct: Boolean): Boolean {
        val ep = Endpoint.parse(url, direct) ?: return false
        endpoint = ep
        api.endpoint = ep
        prefs.serverUrl = url.trim()
        prefs.directBackend = direct
        return true
    }

    /** ログイン前のサーバー確認 */
    suspend fun probeServer(): ServerConfig = api.serverConfig()

    suspend fun signIn(username: String, password: String) {
        val r = api.signIn(username, password)
        prefs.userId = r.userId
        _phase.value = AppPhase.SignedIn(r.userId, ready = false)
        // 読み込みはアプリの scope で行い、呼び出し元はその完了を待つだけにする。呼び出し元 (ログイン画面) は
        // 上の行で画面から外れて scope が取り消されるため、そこで読み込むと途中で止まり
        // 「サーバーから読み込めませんでした」になっていた (#12 で発見)。待つ側が取り消されても読み込みは続く。
        sessionLoadStarted = true
        scope.launch { loadSession(r.userId) }.join()
    }

    suspend fun signUp(username: String, password: String, invite: String?) {
        api.signUp(username, password, invite)
        signIn(username, password)
    }

    private suspend fun loadSession(userId: String) {
        try {
            store.initialLoad(userId)
            _phase.value = AppPhase.SignedIn(userId, ready = true)
        } catch (e: ApiException) {
            if (e.isUnauthorized) signOutLocal()
            else _phase.value = AppPhase.SignedIn(userId, ready = false, error = e.body.ifBlank { e.message })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取り消しは失敗ではない
        } catch (e: Exception) {
            _phase.value = AppPhase.SignedIn(userId, ready = false, error = e.message ?: e.toString())
        }
    }

    /**
     * 保存済みのセッションで起動したときの、サーバーからの読み込みを始める (1 回だけ)。
     * MainActivity の起動・前面への切り替え・常時接続のサービスから呼ぶ。プッシュの受信だけでは呼ばない。
     */
    fun ensureSessionLoaded() {
        val p = _phase.value as? AppPhase.SignedIn ?: return
        synchronized(this) {
            if (sessionLoadStarted || p.ready) return
            sessionLoadStarted = true
        }
        scope.launch {
            snapshotJob?.join() // 前回の内容を出し終えてから最新に置き換える (逆になると古い内容で上書きされる)
            loadSession(p.userId)
        }
    }

    fun retryLoad() {
        val p = _phase.value as? AppPhase.SignedIn ?: return
        _phase.value = p.copy(error = null)
        scope.launch { loadSession(p.userId) }
    }

    suspend fun signOut() {
        // サーバーから端末の登録を消すにはログイン中のトークンが要るので、ログアウトより先に解く
        com.giracle.android.notify.PushRegistrar.onSignOut(appContext, this)
        runCatching { api.signOut() }
        signOutLocal()
    }

    private fun signOutLocal() {
        // セッション切れでも、前のユーザー宛ての通知を受け続けないよう配信先の登録を解く
        if (prefs.pushEndpoint != null) {
            prefs.pushEndpoint = null
            scope.launch(Dispatchers.IO) { runCatching { org.unifiedpush.android.connector.UnifiedPush.unregister(appContext) } }
        }
        store.stop()
        prefs.clearSession()
        com.giracle.android.ui.channel.Drafts.clear(appContext)
        outbox.clear()
        com.giracle.android.ui.ChannelShortcuts.clear(appContext)
        snapshotFile.delete()
        // 状態を捨てて作り直す
        _store.value = newStore()
        sessionLoadStarted = false
        _phase.value = AppPhase.SignedOut
    }

    companion object {
        const val DEFAULT_SERVER = "http://localhost"
        const val UNCAUGHT_TAG = "GiracleUncaught"
    }
}
