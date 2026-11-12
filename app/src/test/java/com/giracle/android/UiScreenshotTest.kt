package com.giracle.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.Prefs
import com.giracle.android.data.net.Endpoint
import com.giracle.android.data.net.GiracleApi
import com.giracle.android.data.net.UploadFile
import com.giracle.android.data.net.buildOkHttp
import com.giracle.android.ui.Root
import com.giracle.android.ui.common.TestTags
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 実サーバーにつないだ実際の画面を描画し、PNG に保存する (目視確認用)。
 * GIRACLE_TEST_SERVER を指定したときだけ動く。管理者 (GIRACLE_TEST_ADMIN_USER / GIRACLE_TEST_ADMIN_PASS、
 * 既定 admin / password123) が居ればよく、デモデータ (seed-demo.sh) には頼らない。
 * テストごとに専用のチャンネルを作り、終わったら消す。要素は文言ではなく testTag (ui/common/TestTags.kt) で探す。
 * 出力先: app/build/screenshots/
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class UiScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val server = System.getenv("GIRACLE_TEST_SERVER")
    private val adminUser = System.getenv("GIRACLE_TEST_ADMIN_USER") ?: "admin"
    private val adminPass = System.getenv("GIRACLE_TEST_ADMIN_PASS") ?: "password123"
    private val out = File("build/screenshots").apply { mkdirs() }
    private val createdChannels = mutableListOf<String>()

    /** 画面の外 (テストの準備・後片付け) からサーバーを操作する、アプリとは別の管理者セッション */
    private val fixture: GiracleApi by lazy {
        val prefs = Prefs(ApplicationProvider.getApplicationContext(), "ui-fixture").apply { clearSession() }
        GiracleApi(buildOkHttp(prefs, null), Endpoint.parse(server!!)!!).also { api ->
            runBlocking { api.signIn(adminUser, adminPass) }
        }
    }

    @After fun tearDown() {
        if (createdChannels.isEmpty()) return
        runBlocking {
            // 未読の通知が残っているとチャンネル削除がサーバー側で失敗するため先に消す (docs/SERVER_CHANGES.md)
            runCatching { fixture.inboxClear() }
            createdChannels.forEach { runCatching { fixture.deleteChannel(it) } }
        }
    }

    private fun newChannel(purpose: String): String = runBlocking {
        val name = "ui-$purpose-${System.currentTimeMillis().toString().takeLast(6)}"
        val id = fixture.createChannel(name, "画面確認テスト用 (自動で消す)").channelId
        createdChannels += id
        runCatching { fixture.joinChannel(id) } // 作成者が自動で参加する版もあるので、失敗は無視する
        id
    }

    private fun send(channelId: String, text: String): String = runBlocking { fixture.send(channelId, text, emptyList(), null).id }

    /** 160x90 のグラデーション画像を添付して送る */
    private fun sendImage(channelId: String, text: String): String {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        for (x in 0 until 160) for (y in 0 until 90) bmp.setPixel(x, y, Color.rgb(255 * x / 160, 255 * y / 90, 200))
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return runBlocking {
            val fileId = fixture.uploadFile(channelId, UploadFile("grad.png", "image/png", png.toRequestBody("image/png".toMediaType())))
            fixture.send(channelId, text, listOf(fileId), null).id
        }
    }

    private fun startApp() {
        val graph = ApplicationProvider.getApplicationContext<GiracleApp>().graph
        graph.setServer(server!!, false)
        graph.prefs.lastChannelId = null // 前回のチャンネルを自動で開かないようにする
        graph.setForeground(true) // Robolectric では前面への切り替えが届かないことがあるので、明示する
        runBlocking { graph.signIn(adminUser, adminPass) }
        rule.setContent { Root(graph, mutableStateOf(null)) }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(m: SemanticsMatcher, ms: Long = 10_000) = rule.waitUntil(ms) { exists(m) }
    private fun waitTag(tag: String, ms: Long = 10_000) = waitFor(hasTestTag(tag), ms)
    private fun waitGone(tag: String, ms: Long = 10_000) = rule.waitUntil(ms) { !exists(hasTestTag(tag)) }
    private fun click(tag: String) { waitTag(tag); rule.onNodeWithTag(tag).performClick() }

    private fun back(expectTag: String) {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag(expectTag)
    }

    /** 履歴の一覧に (どれかのメッセージとして) 指定の文言が出ているか */
    private fun messageWithText(text: String) =
        hasText(text, substring = true) and hasAnyAncestor(hasTestTag(TestTags.CHANNEL_MESSAGES)) and
            SemanticsMatcher("メッセージの行") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("channel.message:") == true }

    @Test fun walkThrough() {
        assumeTrue(server != null)
        val ch = newChannel("walk")
        val greeting = send(ch, "おはようございます！今日もよろしくお願いします")
        send(ch, "おはよう〜 `./gradlew assembleRelease` が通りました")
        send(ch, "資料はこちら https://example.com")
        sendImage(ch, "新しいアイコン案です")
        // メッセージリンクの埋め込み (本文と同じ文言が画面に 2 回出る。文言で探すと取り違える例)
        val latest = send(ch, "さっきの話はこちら &<$ch:$greeting>")
        runBlocking { fixture.react(greeting, ch, "thumbsup"); fixture.react(greeting, ch, "heart") }

        startApp()
        waitTag(TestTags.homeChannel(ch)); shot("01_home")

        click(TestTags.homeChannel(ch))
        waitTag(TestTags.message(latest)); shot("02_channel")

        // 入力欄から送ると、履歴に出る (WebSocket 経由で反映)
        val typed = "画面確認テストからの送信"
        click(TestTags.COMPOSER_INPUT)
        rule.onNodeWithTag(TestTags.COMPOSER_INPUT).performTextInput(typed)
        click(TestTags.COMPOSER_SEND)
        waitFor(messageWithText(typed)); shot("02b_sent")

        // 長押しで操作シート
        rule.onNodeWithTag(TestTags.CHANNEL_MESSAGES).performScrollToNode(hasTestTag(TestTags.message(greeting)))
        waitTag(TestTags.reaction(greeting, "heart"))
        rule.onNodeWithTag(TestTags.message(greeting)).performSemanticsAction(SemanticsActions.OnLongClick)
        waitTag(TestTags.MESSAGE_ACTIONS); shot("03_message_actions")

        // 絵文字ピッカーで 🥳 (hooray) を選ぶとリアクションが付く (WebSocket 経由で反映)
        click(TestTags.MESSAGE_ACTIONS_EMOJI_PICKER)
        waitTag(TestTags.EMOJI_PICKER); shot("04_emoji_picker")
        rule.onNodeWithTag(TestTags.EMOJI_PICKER_SEARCH).performTextInput("hooray")
        click(TestTags.emojiPickerItem("hooray"))
        waitGone(TestTags.EMOJI_PICKER)
        waitTag(TestTags.reaction(greeting, "hooray")); shot("04b_reacted")

        back(TestTags.SCREEN_HOME); shot("04c_after_back")
        click(TestTags.HOME_INBOX); waitTag(TestTags.SCREEN_INBOX); shot("05_inbox")
        back(TestTags.SCREEN_HOME)
        click(TestTags.HOME_MANAGE_SERVER); waitTag(TestTags.SCREEN_MANAGE_SERVER); shot("06_manage")
        back(TestTags.SCREEN_HOME)
        click(TestTags.HOME_SETTINGS); waitTag(TestTags.SCREEN_SETTINGS); shot("07_settings")
        back(TestTags.SCREEN_HOME)
    }

    @Test fun longText() {
        assumeTrue(server != null)
        val ch = newChannel("long")
        val long = send(ch, (1..11).joinToString("\n") { "長文テスト $it 行目" })

        startApp()
        click(TestTags.homeChannel(ch))
        waitTag(TestTags.expandLongText(long)); shot("08_long_text")
        click(TestTags.expandLongText(long))
        waitTag(TestTags.LONG_TEXT_DIALOG); shot("09_long_text_open")
    }

    @Test fun login() {
        assumeTrue(server != null)
        val app = ApplicationProvider.getApplicationContext<GiracleApp>()
        app.graph.prefs.serverUrl = server
        rule.setContent { Root(app.graph, mutableStateOf(null)) }
        waitTag(TestTags.LOGIN_SUBMIT); shot("00_login")
    }
}
