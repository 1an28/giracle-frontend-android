package com.giracle.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.ui.channel.Composer
import com.giracle.android.ui.channel.Drafts
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.TestTags
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 入力欄の送信と下書き (#79)。送信の応答を止めておき、その間に次の文を打つ。
 * サーバーは要らない (送信だけに答える偽のサーバーを使う)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ComposerDraftTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    /** 開けるまで、送信への応答を止める */
    private val release = CountDownLatch(1)
    private val channelId = "ch-draft"

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.url.encodedPath.endsWith("message/send")) return MockResponse.Builder().code(404).build()
                release.await(30, TimeUnit.SECONDS)
                val body = """{"message":"ok","data":{"id":"m1","channelId":"$channelId","userId":"u1","content":"一つ目","createdAt":"2026-11-14T00:00:00.000Z"}}"""
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        Drafts.clear(ctx)
    }

    @After fun tearDown() {
        release.countDown()
        server.close()
        Drafts.clear(ctx)
    }

    /** 端末に書かれた下書き (メモリを通さずに読む) */
    private fun persistedDraft(): String? = ctx.getSharedPreferences("drafts", Context.MODE_PRIVATE).getString(channelId, null)

    /**
     * 画面の時計を進めながら、別のスレッド (偽のサーバー・アプリの scope) の処理も待つ。
     * `waitUntil` だけでは、送信の開始や下書きの保存 (500ms 待つ) まで進まなかった
     */
    private fun await(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "${ms}ms 待っても条件が満たされない" }
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
            Thread.sleep(20)
        }
    }

    @Test fun textTypedWhileSendingSurvivesSuccess() {
        val graph = ApplicationProvider.getApplicationContext<GiracleApp>().graph
        graph.setServer(server.url("/").toString(), true)
        var sentId: String? = null
        rule.setContent {
            CompositionLocalProvider(LocalGraph provides graph, LocalStore provides graph.store) {
                Composer(channelId, replyTo = null, onCancelReply = {}, editing = null, onCancelEdit = {}, onSent = { sentId = it })
            }
        }
        val input = rule.onNodeWithTag(TestTags.COMPOSER_INPUT)
        input.performTextInput("一つ目")
        rule.onNodeWithTag(TestTags.COMPOSER_SEND).performClick()
        await { server.requestCount > 0 } // 送信が偽のサーバーに届き、応答を待っている

        // 送信中に次の文を打ち、手が止まって下書きが端末に書かれるまで待つ
        input.performTextInput("二つ目")
        await { persistedDraft() == "二つ目" }

        release.countDown()
        await { sentId != null }
        rule.waitForIdle()
        assertEquals("m1", sentId)
        // 送れた後も、送信中に打った文は下書きに残る (以前は送れた時点で消えていた)
        assertEquals("二つ目", Drafts.get(ctx, channelId))
        assertEquals("二つ目", persistedDraft())
    }

    /** 送信中に何も打たなければ、送った本文は下書きに残らない */
    @Test fun sentTextIsNotKeptAsDraft() {
        val graph = ApplicationProvider.getApplicationContext<GiracleApp>().graph
        graph.setServer(server.url("/").toString(), true)
        var sentId: String? = null
        rule.setContent {
            CompositionLocalProvider(LocalGraph provides graph, LocalStore provides graph.store) {
                Composer(channelId, replyTo = null, onCancelReply = {}, editing = null, onCancelEdit = {}, onSent = { sentId = it })
            }
        }
        rule.onNodeWithTag(TestTags.COMPOSER_INPUT).performTextInput("一つ目")
        await { persistedDraft() == "一つ目" } // 送る前に下書きが端末に書かれた状態にする
        release.countDown()
        rule.onNodeWithTag(TestTags.COMPOSER_SEND).performClick()
        await { sentId != null }
        rule.waitForIdle()
        assertEquals("", Drafts.get(ctx, channelId))
        assertEquals(null, persistedDraft())
    }
}
