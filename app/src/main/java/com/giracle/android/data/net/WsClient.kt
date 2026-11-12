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
    private var retryJob: Job? = null
    private var pingJob: Job? = null
    private var attempts = 0
    private var everConnected = false

    fun start() {
        wanted = true
        if (socket == null && _state.value != State.Connecting) open()
    }

    fun stop() {
        wanted = false
        retryJob?.cancel()
        pingJob?.cancel()
        socket?.close(1000, null)
        socket = null
        _state.value = State.Disconnected
    }

    /** アプリが前面に戻ったときなど、すぐに再接続を試す */
    fun nudge() {
        if (!wanted) return
        if (_state.value == State.Disconnected) {
            retryJob?.cancel()
            attempts = 0
            open()
        }
    }

    private fun open() {
        val ep = endpointProvider()
        _state.value = State.Connecting
        val req = Request.Builder()
            .url(ep.wsUrl)
            .header("Origin", ep.originHeader)
            .build()
        socket = client.newWebSocket(req, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            attempts = 0
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
            scope.launch { _frames.emit(frame) }
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
            if (!wanted) return
            val backoff = (1000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000L)
            attempts++
            retryJob = scope.launch {
                delay(backoff + Random.nextLong(500))
                if (wanted && socket == null) open()
            }
        }
    }

    companion object {
        private const val TAG = "GiracleWs"
    }
}
