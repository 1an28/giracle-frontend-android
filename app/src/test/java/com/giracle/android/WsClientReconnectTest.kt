package com.giracle.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.Prefs
import com.giracle.android.data.net.Endpoint
import com.giracle.android.data.net.WsClient
import com.giracle.android.data.net.buildOkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 接続中のまま固まった試みを、回線がつながったとき (nudge) に捨ててつなぎ直す (#101)。
 * 圏外で起動した e2e で、WebSocket が「接続中」から抜けず、送信待ちが送られなかった。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WsClientReconnectTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 接続は受けるが、何も返さないサーバー (固まった試みを作る) */
    private val silent = ServerSocket(0)
    private val accepted = mutableListOf<Socket>()
    private val server = MockWebServer()

    @After fun tearDown() {
        scope.cancel()
        accepted.forEach { runCatching { it.close() } }
        silent.close()
        server.close()
    }

    private fun waitFor(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "${ms}ms 待っても条件が満たされない" }
            Thread.sleep(20)
        }
    }

    @Test fun nudgeDropsStuckAttemptAndReconnects() {
        thread(isDaemon = true) { runCatching { while (true) { val s = silent.accept(); synchronized(accepted) { accepted += s } } } }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            // 後始末で偽のサーバーを閉じられるよう、閉じの合図に応える
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).build())
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val http = buildOkHttp(Prefs(ctx, "test-ws"), null)
        // 最初は固まるサーバーへ、回線が戻った後は応答するサーバーへつなぐ (電波が戻ったのと同じ)
        var ep = Endpoint.parse("http://127.0.0.1:${silent.localPort}")!!
        val ws = WsClient(http, scope, endpointProvider = { ep }, staleConnectMs = 0)

        ws.start()
        waitFor { synchronized(accepted) { accepted.isNotEmpty() } }
        assertEquals(WsClient.State.Connecting, ws.state.value)
        Thread.sleep(5) // staleConnectMs = 0 より確実に経たせる

        ep = Endpoint.parse(server.url("/").toString())!!
        ws.nudge()
        waitFor { ws.state.value == WsClient.State.Connected }
        assertEquals(1, server.requestCount) // 固まった試みの後の、新しい試みだけが届いた
        ws.stop()
    }

    /** 始めたばかりの試みは捨てない (起動直後に回線の知らせが来ても、つなぎ直しを繰り返さない) */
    @Test fun nudgeKeepsFreshAttempt() {
        thread(isDaemon = true) { runCatching { while (true) { val s = silent.accept(); synchronized(accepted) { accepted += s } } } }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val http = buildOkHttp(Prefs(ctx, "test-ws2"), null)
        val ep = Endpoint.parse("http://127.0.0.1:${silent.localPort}")!!
        val ws = WsClient(http, scope, endpointProvider = { ep }, staleConnectMs = 60_000)
        ws.start()
        waitFor { synchronized(accepted) { accepted.size == 1 } }
        ws.nudge()
        Thread.sleep(500)
        assertEquals(1, synchronized(accepted) { accepted.size })
        assertEquals(WsClient.State.Connecting, ws.state.value)
        ws.stop()
    }

    /** 回線の知らせが来なくても、固まった試みは見張りが取り消し、つなぎ直す */
    @Test fun watchdogRetriesStuckAttempt() {
        thread(isDaemon = true) { runCatching { while (true) { val s = silent.accept(); synchronized(accepted) { accepted += s } } } }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val http = buildOkHttp(Prefs(ctx, "test-ws3"), null)
        val ep = Endpoint.parse("http://127.0.0.1:${silent.localPort}")!!
        val ws = WsClient(http, scope, staleConnectMs = 60_000, connectWatchdogMs = 300) { ep }
        ws.start()
        // 1 回目が固まり、見張りが取り消し、待ち時間 (約 1 秒) の後に 2 回目の試みが届く
        waitFor { synchronized(accepted) { accepted.size >= 2 } }
        ws.stop()
    }
}
