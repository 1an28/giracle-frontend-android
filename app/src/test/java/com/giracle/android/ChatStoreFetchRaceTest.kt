package com.giracle.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.ChatStore
import com.giracle.android.data.Prefs
import com.giracle.android.data.Snapshot
import com.giracle.android.data.model.Message
import com.giracle.android.data.model.User
import com.giracle.android.data.net.Endpoint
import com.giracle.android.data.net.GiracleApi
import com.giracle.android.data.net.WsClient
import com.giracle.android.data.net.buildOkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 履歴を取り直している間に届いた新着が、取り直しの結果で消えない (#84)。
 * 電波が戻った後、再同期の取り直しと送信待ちの送信が重なり、送ったものがサーバーには届くのに一覧に出なかった。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChatStoreFetchRaceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer()
    private val requested = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @After fun tearDown() {
        release.countDown()
        scope.cancel()
        server.close()
    }

    private fun msg(id: String, t: String) = Message(id = id, channelId = "ch", userId = "u1", content = "m$id", createdAt = t)
    private fun json(m: Message) = """{"id":"${m.id}","channelId":"ch","userId":"u1","content":"${m.content}","createdAt":"${m.createdAt}"}"""

    private fun store(): ChatStore {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.url.encodedPath.contains("channel/get-history")) return MockResponse.Builder().code(404).build()
                requested.countDown()
                release.await(30, TimeUnit.SECONDS)
                // 取りに行った時点の内容 (新着 3 はまだ無い)
                val body = """{"message":"ok","data":{"history":[${json(msg("2", "2026-11-14T00:00:02.000Z"))},${json(msg("1", "2026-11-14T00:00:01.000Z"))}],"atTop":true,"atEnd":true}}"""
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(ctx, "test-fetch-race")
        val ep = Endpoint.parse(server.url("/").toString())!!
        val http = buildOkHttp(prefs, null)
        val api = GiracleApi(http, ep)
        return ChatStore(api, WsClient(http, scope) { ep }, prefs, scope)
    }

    @Test fun messageArrivingDuringReopenIsKept() = runBlocking {
        val store = store()
        // 前回の内容 (キャッシュの印付き) から始める。再同期の取り直しの前と同じ状態
        store.restore(Snapshot(userId = "u1", me = User(id = "u1", name = "me"), histories = mapOf("ch" to listOf(msg("1", "2026-11-14T00:00:01.000Z")))))
        val open = scope.async { store.openChannel("ch") }
        check(requested.await(10, TimeUnit.SECONDS)) { "履歴の取得が始まらない" }
        // 取り直しの最中に、送信待ちの送信の応答 (または WebSocket の新着) が届く
        store.addMessage(msg("3", "2026-11-14T00:00:03.000Z"))
        release.countDown()
        open.await()
        assertEquals(listOf("3", "2", "1"), store.history("ch").value.messages.map { it.id })
    }
}
