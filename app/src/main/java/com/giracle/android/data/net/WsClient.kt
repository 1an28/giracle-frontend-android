package com.giracle.android.data.net

import android.util.Log
import com.giracle.android.data.model.WsFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/**
 * Giracle の WebSocket (`/ws`)。
 *
 * - 認証は Cookie (OkHttp の TokenInterceptor が付ける)。
 * - バックエンドは Origin ヘッダが無いと 403 を返すため、サーバー URL から作った Origin を付ける。
 * - 切断されたら指数バックオフ (最大 30 秒) + ジッターで再接続する。
 * - "ERROR" シグナル (トークン無効など) を受けたら再接続しない。
 */
class WsClient(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    /** この時間より長く「接続中」のままの試みは、回線が変わったとき ([nudge]) に捨ててやり直す (#101) */
    private val staleConnectMs: Long = 3_000L,
    /** この時間たっても「接続中」の試みは、回線の知らせを待たずに取り消して、いつものつなぎ直しに回す (#101) */
    private val connectWatchdogMs: Long = 20_000L,
    private val endpointProvider: () -> Endpoint,
) {
    enum class State { Disconnected, Connecting, Connected, AuthError }

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _frames = MutableSharedFlow<WsFrame>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val frames: SharedFlow<WsFrame> = _frames

    /** 再接続のたびに +1。購読側は「再接続したので取りこぼしを再取得する」判断に使う */
    private val _reconnects = MutableStateFlow(0)
    val reconnects: StateFlow<Int> = _reconnects

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted = false
    /** 今の試みを始めた時刻 (経過時間の時計)。接続中のまま固まった試みを見分ける (#101) */
    @Volatile private var openedAt = 0L
    private var retryJob: Job? = null
    private var pingJob: Job? = null
    private var watchdogJob: Job? = null
    private var attempts = 0
    private var everConnected = false

    fun start() {
        wanted = true
        if (socket == null && _state.value != State.Connecting) {
            retryJob?.cancel()
            attempts = 0
            open()
        }
    }

    fun stop() {
        wanted = false
        retryJob?.cancel()
        pingJob?.cancel()
        watchdogJob?.cancel()
        socket?.close(1000, null)
        socket = null
        _state.value = State.Disconnected
    }

    /**
     * 回線がつながったときなど、すぐに再接続を試す。
     * 接続中のまま [staleConnectMs] より経った試みも捨ててやり直す。圏外の間に始めた試みが固まり、
     * 電波が戻っても「接続中」のまま再接続されず、送信待ちが送られないことがあった (#101、e2e の記録で確かめた)
     */
    fun nudge() {
        if (!wanted) return
        val stale = _state.value == State.Connecting && System.nanoTime() / 1_000_000 - openedAt > staleConnectMs
        if (_state.value == State.Disconnected || stale) {
            if (stale) {
                Log.i(TAG, "drop stale attempt")
                socket?.cancel()
                socket = null
            }
            retryJob?.cancel()
            attempts = 0
            open()
        }
    }

    private fun open() {
        val ep = endpointProvider()
        Log.i(TAG, "opening (attempt $attempts)")
        openedAt = System.nanoTime() / 1_000_000
        _state.value = State.Connecting
        val req = Request.Builder()
            .url(ep.wsUrl)
            .header("Origin", ep.originHeader)
            .build()
        val s = client.newWebSocket(req, Listener())
        socket = s
        // 固まった試みの見張り。取り消すと onFailure が呼ばれ、onEnd から待ち時間を置いてつなぎ直す
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(connectWatchdogMs)
            if (socket === s && _state.value == State.Connecting) {
                // 先に外してから取り消す。取り消しの通知 (onFailure) を待たない:
                // 名前解決の途中などでは、取り消しても通知がすぐには来ないため (外した後の通知は socket !== s で無視される)
                Log.i(TAG, "connect watchdog: drop stuck attempt")
                socket = null
                s.cancel()
                _state.value = State.Disconnected
                scheduleRetry()
            }
        }
    }

    /** 待ち時間 (1 秒から倍々、最長 30 秒) を置いてつなぎ直す */
    private fun scheduleRetry() {
        if (!wanted) return
        val backoff = (1000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000L)
        attempts++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(backoff + Random.nextLong(500))
            if (wanted && socket == null) open()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            attempts = 0
            Log.i(TAG, "connected") // 送信待ちの送り直しのきっかけ (#101)
            _state.value = State.Connected
            if (everConnected) _reconnects.value += 1
            everConnected = true
            pingJob?.cancel()
            pingJob = scope.launch {
                // Web 版と同じ 20 秒間隔のアプリ層 ping
                while (isActive) {
                    delay(20_000)
                    webSocket.send("""{"signal":"ping","data":"ping"}""")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val frame = runCatching { AppJson.decodeFromString(WsFrame.serializer(), text) }.getOrNull() ?: return
            if (frame.signal == "pong") return
            if (frame.signal == "ERROR") {
                Log.w(TAG, "server error: ${frame.data}")
                _state.value = State.AuthError
                wanted = false
                webSocket.close(1000, null)
                return
            }
            // 受け取った順に渡す。以前は 1 件ごとに launch しており、複数のスレッドで動くため
            // 「送信」より先に「編集」が届くなど、順番が入れ替わることがあった。
            // 普段は空きがあるので tryEmit で済む。詰まったときだけ受信スレッドを待たせる (取りこぼさない)
            if (!_frames.tryEmit(frame)) runBlocking { _frames.emit(frame) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onEnd(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "ws failure: ${t.message} (${response?.code})")
            if (response?.code == 403 || response?.code == 401) {
                // Origin 不一致やトークン無効。再接続しても直らない
                if (webSocket === socket) {
                    socket = null
                    pingJob?.cancel()
                    _state.value = State.AuthError
                }
                return
            }
            onEnd(webSocket)
        }

        private fun onEnd(webSocket: WebSocket) {
            if (webSocket !== socket) return
            socket = null
            pingJob?.cancel()
            if (_state.value == State.AuthError) return
            _state.value = State.Disconnected
            scheduleRetry()
        }
    }

    companion object {
        private const val TAG = "GiracleWs"
    }
}
