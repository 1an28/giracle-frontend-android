package com.giracle.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * 配布版 APK をエミュレータで外から操作して、通しで確かめる (#12)。
 *
 * 引数 (am instrument -e):
 *   appPackage  確かめるアプリ (既定 io.github.u1an28.giracle.kotlin)
 *   server      端末から見たサーバー (既定 http://localhost:3333。CI は adb reverse で手元のサーバーにつなぐ)
 *   adminUser / adminPass  サーバーの管理者 (既定 admin / password123)
 *
 * 1 つの流れにしてあるのは、ログイン → チャンネル → 背面 → 設定 と、前の段階の状態を引き継ぐため。
 * 各段階の画面を [shots] に保存する (CI が取り出して成果物にする)。
 */
@RunWith(AndroidJUnit4::class)
class ReleaseAppTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(instr)
    private val pkg = args.getString("appPackage") ?: "io.github.u1an28.giracle.kotlin"
    private val server = args.getString("server") ?: "http://localhost:3333"
    private val adminUser = args.getString("adminUser") ?: "admin"
    private val adminPass = args.getString("adminPass") ?: "password123"
    private val shots = File(instr.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    private val suffix = System.currentTimeMillis().toString().takeLast(6)

    private val admin = ServerApi(server)
    private val bob = ServerApi(server)
    private lateinit var channelId: String
    private var step = 0

    @Before fun setUp() {
        // 管理者で招待コードを作り、相手役 (bob) を登録する。確認用のチャンネルを作り、2 人とも参加する
        admin.signIn(adminUser, adminPass)
        val invite = "e2e$suffix"
        admin.createInvite(invite, 2)
        bob.signUp("bob$suffix", "pass1234", invite)
        bob.signIn("bob$suffix", "pass1234")
        channelId = admin.createChannel("e2e$suffix", "配布版の確認用 (自動で消す)")
        runCatching { admin.joinChannel(channelId) } // 作成者が自動で参加する版もあるので失敗は無視する
        bob.joinChannel(channelId)
    }

    @After fun tearDown() {
        shot("last")
        runCatching { admin.deleteChannel(channelId) }
    }

    @Test fun releaseBuildWorksEndToEnd() {
        // 1. 起動 (まっさらな状態から)。R8 で必要なものが消えていれば、ここかログインで落ちる
        shell("pm clear $pkg")
        launch()
        val url = wait(res("login.server_url"), 30_000, "ログイン画面")
        shot("launch")

        // 2. サーバーを指定してログイン
        url.text = server
        wait(res("login.connect"), 5_000, "接続ボタン").click()
        wait(res("login.username"), 20_000, "サーバー情報の取得 (login.username)").text = adminUser
        wait(res("login.password"), 5_000, "パスワード欄").text = adminPass
        hideKeyboard() // ボタンを隠さないように
        wait(res("login.submit"), 5_000, "ログインボタン").click()

        // 3. 通知の権限の確認 (Android 13 以上)。許可しても落ちないこと
        val allow = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_allow_button"))), 20_000)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (allow == null) fail("通知の権限の確認画面が出ない")
            shot("permission")
            allow!!.click()
        }
        wait(res("screen.home"), 20_000, "ホーム画面")
        assertAlive("通知の権限を許可した後")
        shot("home")

        // 4. チャンネルを開く
        findScrolling(res("home.channel:$channelId"), "確認用チャンネル").click()
        wait(res("screen.channel"), 10_000, "チャンネル画面")

        // 5. 送信: 画面に出て、サーバーにも届いている
        val sent = "送信テスト $suffix"
        wait(res("composer.input"), 10_000, "入力欄").apply { click(); text = sent }
        wait(res("composer.send"), 5_000, "送信ボタン").click()
        wait(By.textContains(sent), 15_000, "自分の送信")
        waitFor("サーバーに送信が届く") { admin.recentTexts(channelId).contains(sent) }
        shot("sent")

        // 6. 受信 (WebSocket): 相手の送信が、取り直さずに画面に出る
        val received = "受信テスト $suffix"
        bob.send(channelId, received)
        wait(By.textContains(received), 15_000, "相手の送信の受信 (WebSocket)")
        shot("received")

        // 6b. リアクション (#20): 長押し → 絵文字ピッカーで 🥳 を付け、もう一度押して外す。サーバーにも反映される
        val receivedId = admin.recent(channelId).first { it.content == received }.id
        wait(res("channel.message:$receivedId"), 5_000, "受信したメッセージ").longClick()
        wait(res("message_actions.emoji_picker"), 5_000, "操作シートのリアクションボタン").click()
        wait(res("emoji_picker.search"), 5_000, "絵文字の検索欄").text = "hooray"
        wait(res("emoji_picker.item:hooray"), 10_000, "絵文字ピッカーの 🥳").click()
        val chip = res("channel.reaction:$receivedId:hooray")
        wait(chip, 15_000, "付けたリアクション (WebSocket で反映)")
        waitFor("サーバーにリアクションが付く") { admin.recent(channelId).first { it.id == receivedId }.reactions["hooray"] == 1 }
        shot("reacted")
        wait(chip, 5_000, "リアクション").click()
        if (!device.wait(Until.gone(chip), 15_000)) fail("押したリアクションが外れない")
        waitFor("サーバーのリアクションが外れる") { admin.recent(channelId).first { it.id == receivedId }.reactions["hooray"] == null }

        // 6c. 他のアプリからの共有 (#4) で、画像と文書ファイルを送る。送り先はホームで選ぶ。
        //     アップロード (#20)、画像の読み込みと表示 (Coil。R8 後)、ファイルの表示まで確かめる
        val png = "e2e-$suffix.png"
        val txt = "e2e-$suffix.txt"
        writeShareFiles(png, txt)
        val uris = arrayListOf(shareUri(png), shareUri(txt))
        val share = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            putExtra(android.content.Intent.EXTRA_TEXT, "共有テスト $suffix")
            clipData = android.content.ClipData.newRawUri("", uris[0]).apply { addItem(android.content.ClipData.Item(uris[1])) }
            setPackage(pkg)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        instr.context.startActivity(share)
        wait(res("home.share_banner"), 15_000, "共有の送り先を選ぶ案内 (ホーム)")
        shot("share_home")
        findScrolling(res("home.channel:$channelId"), "確認用チャンネル (共有の送り先)").click()
        wait(res("screen.channel"), 10_000, "共有の送り先のチャンネル画面")
        waitFor("添付のアップロードが終わり、送信できるようになる", 30_000) { device.findObject(res("composer.send"))?.isEnabled == true }
        shot("share_composer")
        wait(res("composer.send"), 5_000, "送信ボタン").click()
        var sharedMsg: ServerApi.Msg? = null
        waitFor("サーバーに共有の投稿 (添付 2 つと本文) が届く", 20_000) {
            sharedMsg = admin.recent(channelId).firstOrNull { it.content.contains("共有テスト $suffix") && it.files.size == 2 }
            sharedMsg != null
        }
        val imageId = sharedMsg!!.files.first { it.second.startsWith("image/") }.first
        wait(res("channel.image_loaded:$imageId"), 30_000, "送った画像の読み込みと表示")
        wait(By.text(txt), 10_000, "送った文書ファイルの表示")
        shot("shared")
        assertAlive("共有の投稿の後")

        // 7. 背面に回して戻る: 背面の間の投稿が戻ったときに取り直され、その後の投稿も WebSocket で届く
        device.pressHome()
        SystemClock.sleep(5_000) // 背面では切断する設定 (常時接続 OFF) なので、切断を待つ
        assertAlive("背面に回した後")
        val whileAway = "背面中の投稿 $suffix"
        bob.send(channelId, whileAway)
        SystemClock.sleep(2_000)
        launch()
        wait(res("screen.channel"), 15_000, "戻ったときのチャンネル画面")
        wait(By.textContains(whileAway), 20_000, "背面の間の投稿 (再接続後の取り直し)")
        val afterReturn = "復帰後の投稿 $suffix"
        bob.send(channelId, afterReturn)
        wait(By.textContains(afterReturn), 15_000, "戻った後の受信 (再接続した WebSocket)")
        shot("resumed")

        // 8. 常時接続 (前面サービス) を ON にしても落ちない。背面でも接続が保たれ、メンションが通知される
        hideKeyboard()
        device.pressBack()
        wait(res("screen.home"), 10_000, "ホーム画面に戻る")
        wait(res("home.settings"), 5_000, "設定ボタン").click()
        wait(res("screen.settings"), 10_000, "設定画面")
        wait(By.text("通知"), 5_000, "通知タブ").click()
        val keep = res("settings.keep_connection")
        findScrolling(keep, "常時接続の設定").let { it.findObject(By.checkable(true)) ?: it }.click()
        waitFor("常時接続の設定が ON になる") { device.findObject(keep)?.findObject(By.checkable(true))?.isChecked == true }
        shot("keep_connection")
        waitFor("前面サービスが動く", 15_000) { shell("dumpsys activity services $pkg").contains("isForeground=true") }
        assertAlive("常時接続を ON にした後")

        device.pressHome()
        SystemClock.sleep(5_000)
        assertAlive("常時接続のまま背面に回した後")
        assertTrue("背面で前面サービスが止まった", shell("dumpsys activity services $pkg").contains("isForeground=true"))
        val mention = "通知テスト @<${admin.userId}> $suffix"
        bob.send(channelId, mention)
        device.openNotification()
        // 通知の本文はメンションを名前に置き換えるので、末尾の識別子だけで探す
        val n = device.wait(Until.findObject(By.textContains("通知テスト")), 20_000)
        shot("notification")
        if (n == null) fail("常時接続で背面にいるときのメンションが通知されない")
        device.pressBack()
        assertAlive("通知を受けた後")

        // 9. プッシュ通知 (D-019): ログイン後に自動で有効になり (配信アプリが無いので Google Play 開発者サービス経由)、
        //    アプリのプロセスが無い状態でも、サーバーの Web Push が FCM 経由で届いて通知になる
        launch()
        wait(res("screen.settings"), 10_000, "設定画面に戻る")
        wait(By.text("通知"), 5_000, "通知タブ").click()
        findScrolling(keep, "常時接続の設定").let { it.findObject(By.checkable(true)) ?: it }.click()
        waitFor("常時接続の設定が OFF になる") { device.findObject(keep)?.findObject(By.checkable(true))?.isChecked == false }
        waitFor("前面サービスが止まる", 15_000) { !shell("dumpsys activity services $pkg").contains("isForeground=true") }
        val status = res("settings.push_status")
        findScrolling(status, "プッシュ通知の状態")
        if (!pushEnabled(status, 60_000)) {
            // 自動の登録が済んでいなければ、ボタンで登録する (Google Play 開発者サービスの準備が遅いことがある)
            Log.w(TAG, "自動では有効になっていない: " + device.findObject(status)?.text)
            findScrolling(res("settings.push_enable"), "プッシュ通知の有効化ボタン").click()
            if (!pushEnabled(status, 90_000)) {
                shot("push_status")
                fail("プッシュ通知が有効にならない: " + device.findObject(status)?.text)
            }
        }
        Log.i(TAG, "プッシュ通知の状態: " + device.findObject(status)?.text)
        shot("push_enabled")

        device.pressHome()
        SystemClock.sleep(3_000)
        // 背面のプロセスを終わらせる (利用者がアプリを使っていない間に OS が終わらせるのと同じ状態)
        shell("am kill $pkg")
        val killed = runCatching { waitFor("背面のプロセスが終わる", 10_000) { shell("pidof $pkg").isBlank() } }.isSuccess
        Log.i(TAG, "プロセスを終わらせた: $killed")
        val pushText = "プッシュテスト @<${admin.userId}> $suffix"
        bob.send(channelId, pushText)
        device.openNotification()
        val pn = device.wait(Until.findObject(By.textContains("プッシュテスト")), 60_000)
        shot("push_notification")
        if (pn == null) {
            // 手がかり: アプリの通知の一覧と、アプリのプロセスがあるか
            Log.w(TAG, "notifications:\n" + shell("dumpsys notification --noredact").lines().filter { pkg in it || "android.title" in it || "android.text" in it }.take(40).joinToString("\n"))
            fail("プッシュ通知が届かない (プロセスを終わらせた: $killed、今のプロセス: ${shell("pidof $pkg").trim()})")
        }
        device.pressBack()
    }

    /** 共有するファイル (64x64 の PNG と、短い文書) をキャッシュの share/ に作る */
    private fun writeShareFiles(png: String, txt: String) {
        val dir = File(instr.context.cacheDir, "share").apply { mkdirs() }
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.rgb(0x33, 0x99, 0xcc))
        File(dir, png).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, txt).writeText("共有テストの文書 $suffix\n")
    }

    private fun shareUri(name: String): android.net.Uri = android.net.Uri.parse("content://${ShareProvider.AUTHORITY}/$name")

    private fun pushEnabled(status: BySelector, timeoutMs: Long): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (device.findObject(status)?.text?.startsWith("有効") == true) return true
            SystemClock.sleep(1_000)
        }
        return false
    }

    // ---------- 補助 ----------

    /** ソフトウェアキーボードが出ていれば閉じる (出ていないときに「戻る」を押すと画面を離れてしまうので確かめる) */
    private fun hideKeyboard() {
        if (shell("dumpsys input_method").contains("mInputShown=true")) {
            device.pressBack()
            SystemClock.sleep(500)
        }
    }

    private fun res(tag: String): BySelector = By.res(tag)

    /** ランチャーから開くのと同じ画面を起動する (起動画面のクラス名は端末に問い合わせる) */
    private fun launch() {
        val component = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg")
            .lines().map { it.trim() }.lastOrNull { it.contains('/') }
            ?: error("$pkg の起動画面が見つからない (インストールされていない?)")
        shell("am start -W -n $component")
    }

    private fun wait(sel: BySelector, timeoutMs: Long, what: String): UiObject2 =
        waitDismissingSystemDialogs(sel, timeoutMs) ?: run {
            assertAlive("「$what」を待つ間")
            shot("missing")
            fail("「$what」が ${timeoutMs / 1000} 秒以内に出ない"); error("unreachable")
        }

    /**
     * 要素を待つ。間に、ほかのアプリの「応答していません」ダイアログ (エミュレータが重いときに出る) が
     * 画面を覆ったら「待つ」を押して閉じる。アプリ自身の不具合ではないので、確認を止めない。
     */
    private fun waitDismissingSystemDialogs(sel: BySelector, timeoutMs: Long): UiObject2? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            device.findObject(By.res("android:id/aerr_wait"))?.let {
                Log.w(TAG, "システムのダイアログを閉じた: " + device.findObject(By.res("android:id/alertTitle"))?.text)
                it.click()
            }
            val left = end - SystemClock.uptimeMillis()
            if (left <= 0) return null
            device.wait(Until.findObject(sel), minOf(left, 2_000L))?.let { return it }
        }
    }

    /** 一覧を送りながら探す (画面の外にある行・タブ用) */
    private fun findScrolling(sel: BySelector, what: String): UiObject2 {
        repeat(8) {
            device.wait(Until.findObject(sel), 2_000)?.let { return it }
            val scrollable = device.findObject(By.scrollable(true)) ?: return@repeat
            scrollable.scroll(Direction.DOWN, 0.8f)
        }
        return wait(sel, 2_000, what)
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            SystemClock.sleep(500)
        }
        shot("timeout")
        fail("$what (${timeoutMs / 1000} 秒待った)")
    }

    private fun assertAlive(whenDoing: String) {
        if (shell("pidof $pkg").isBlank()) fail("$whenDoing にアプリのプロセスが無い (落ちた可能性。logcat の crash バッファを見る)")
    }

    private fun shell(cmd: String): String = device.executeShellCommand(cmd).also { Log.d(TAG, "$ $cmd\n" + it.take(300)) }

    private fun shot(name: String) {
        runCatching { device.takeScreenshot(File(shots, "%02d-%s.png".format(step++, name))) }
    }

    companion object { private const val TAG = "GiracleE2E" }
}
