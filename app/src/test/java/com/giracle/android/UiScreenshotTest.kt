package com.giracle.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.ui.Root
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 実サーバーにつないだ実際の画面を描画し、PNG に保存する (目視確認用)。
 * devtools/seed-demo.sh のデータが入ったサーバーを GIRACLE_TEST_SERVER で指定したときだけ動く。
 * 出力先: app/build/screenshots/
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class UiScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val server = System.getenv("GIRACLE_TEST_SERVER")
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(t: String, ms: Long = 10_000) = rule.waitUntil(ms) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun walkThrough() {
        assumeTrue(server != null)
        val app = ApplicationProvider.getApplicationContext<GiracleApp>()
        val graph = app.graph
        graph.setServer(server!!, false)
        runBlocking { graph.signIn("admin", "password123") }
        rule.setContent { Root(graph, mutableStateOf(null)) }

        waitText("雑談"); shot("01_home")
        rule.onNodeWithText("雑談").performClick()
        waitText("新しいアイコン案です"); Thread.sleep(1500); shot("02_channel")
        rule.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(hasText("今日もよろしく", substring = true) and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)); rule.waitForIdle()
        rule.onAllNodes(hasText("おはようございます", substring = true) and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        waitText("返信"); shot("03_message_actions")
        rule.onNodeWithContentDescription("絵文字を選ぶ").performClick()
        Thread.sleep(800); shot("04_emoji_picker")
        // ピッカーで 🎉 を選ぶとリアクションが付く (WS 経由で反映)
        rule.onAllNodesWithText("🥳").onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("🥳").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500); shot("04b_reacted")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle(); Thread.sleep(500); shot("04c_after_back")
        waitText("参加チャンネル")
        rule.onNodeWithText("通知").performClick(); waitText("日付別"); shot("05_inbox")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("サーバー管理").performClick(); waitText("サーバー設定を保存"); shot("06_manage")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithContentDescription("設定").performClick(); waitText("プロフィール"); shot("07_settings")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("開発").performClick(); waitText("長文メッセージを展開する"); shot("08_long_text")
        rule.onNodeWithText("長文メッセージを展開する").performClick(); Thread.sleep(500); shot("09_long_text_open")
    }

    @Test fun login() {
        assumeTrue(server != null)
        val app = ApplicationProvider.getApplicationContext<GiracleApp>()
        app.graph.prefs.serverUrl = server
        rule.setContent { Root(app.graph, mutableStateOf(null)) }
        waitText("ログイン"); Thread.sleep(500); shot("00_login")
    }
}
