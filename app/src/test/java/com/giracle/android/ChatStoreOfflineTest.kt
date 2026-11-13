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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * サーバーにつながらないとき (圏外・再接続の直後に通信が落ちたとき) に、ストアが例外を外へ漏らさず、
 * 表示中の内容を消さないことを確かめる。サーバーは要らない (閉じたポートにつなぐ)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChatStoreOfflineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private fun offlineStore(): ChatStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(ctx, "test-offline")
        // 9 番 (discard) は普通は開いていないので、すぐに接続を断られる
        val ep = Endpoint.parse("http://127.0.0.1:9")!!
        val http = buildOkHttp(prefs, null)
        val api = GiracleApi(http, ep)
        return ChatStore(api, WsClient(http, scope) { ep }, prefs, scope)
    }

    private fun msg(id: String, t: String) = Message(id = id, channelId = "ch", userId = "u1", content = "m$id", createdAt = t)

    @Test fun resyncKeepsCurrentChannelWhenOffline() = runBlocking {
        val store = offlineStore()
        store.restore(
            Snapshot(
                userId = "u1", me = User(id = "u1", name = "me"),
                histories = mapOf("ch" to listOf(msg("2", "2026-11-13T00:00:02Z"), msg("1", "2026-11-13T00:00:01Z"))),
            ),
        )
        // 開く (サーバーから取り直そうとして失敗する。開いている扱いにはなる)
        runCatching { store.openChannel("ch") }
        // 再接続後の再同期で通信が失敗しても、例外を外へ出さない (以前はここでアプリが落ちていた)
        store.resync()
        val h = store.history("ch").value
        assertTrue("表示中の内容が残る", h.loaded)
        assertEquals(listOf("2", "1"), h.messages.map { it.id })
        // 取り直しが済んでいない印が残り、次に開いたときに取り直す
        assertTrue(h.fromCache)
    }

    /**
     * 再接続の後も、開いていないチャンネルの履歴を捨てずに「キャッシュから」として残す (C-2)。
     * 次に開いたとき、取り直しを待たずにすぐ出せる。最新側に揃っていない履歴は捨てる
     */
    @Test fun resyncKeepsOtherChannelsAsCache() = runBlocking {
        val store = offlineStore()
        val many = (100 downTo 1).map { msg("$it", "2026-11-13T00:%02d:%02dZ".format(it / 60, it % 60)) }
        store.restore(
            Snapshot(
                userId = "u1", me = User(id = "u1", name = "me"),
                histories = mapOf("ch" to listOf(msg("2", "2026-11-13T00:00:02Z")), "other" to many),
            ),
        )
        runCatching { store.openChannel("ch") }
        store.resync()
        val h = store.history("other").value
        assertTrue("開いていないチャンネルの履歴が残る", h.loaded)
        assertTrue("開いたときに取り直す印", h.fromCache)
        assertEquals("最新側から件数を抑えて残す", (100 downTo 41).map { "$it" }, h.messages.map { it.id })
    }

    /**
     * キャッシュ (切れていた間の投稿が抜けているかもしれない) には新着を足さず、既読にもしない (#59 のレビュー)。
     * 足すと抜けたまま新着だけが続いて見え、既読にすると見ていない投稿まで既読になる
     */
    @Test fun cacheIsNotExtendedNorMarkedRead() = runBlocking {
        val store = offlineStore()
        store.restore(
            Snapshot(
                userId = "u1", me = User(id = "u1", name = "me"),
                histories = mapOf("ch" to listOf(msg("2", "2026-11-13T00:00:02Z"))),
                readTimes = mapOf("ch" to "2026-11-13T00:00:01Z"),
            ),
        )
        store.addMessage(msg("9", "2026-11-13T00:00:09Z"))
        assertEquals("キャッシュに新着を足さない", listOf("2"), store.history("ch").value.messages.map { it.id })
        store.markRead("ch")
        assertEquals("キャッシュでは既読にしない", "2026-11-13T00:00:01Z", store.readTimes.value["ch"])
    }
}
