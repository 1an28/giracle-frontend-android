package com.giracle.baselineprofile

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 記録・計測の共通部分。引数 (am instrument -e) は e2e (ReleaseAppTest) と同じ:
 *   appPackage (既定 io.github.u1an28.giracle.kotlin)、server (既定 http://localhost:3333)、adminUser / adminPass
 */
internal object Setup {
    private val args: Bundle get() = InstrumentationRegistry.getArguments()
    val pkg: String get() = args.getString("appPackage") ?: "io.github.u1an28.giracle.kotlin"
    val server: String get() = args.getString("server") ?: "http://localhost:3333"
    private val adminUser get() = args.getString("adminUser") ?: "admin"
    private val adminPass get() = args.getString("adminPass") ?: "password123"

    /** 記録用のチャンネル ID (1 回だけ作る) */
    @Volatile private var channelId: String? = null

    /**
     * 記録用のチャンネルを作り、スクロールできるだけのメッセージを入れる。
     * 通知の権限は先に与えておく (ログイン後の確認画面で止まらないように)。
     */
    fun prepare(): String {
        channelId?.let { return it }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        val api = Api(server)
        api.signIn(adminUser, adminPass)
        val id = api.call("PUT", "channel/create", JSONObject().put("channelName", "bp" + System.currentTimeMillis().toString().takeLast(6)).put("description", "Baseline Profile の記録用"))
            .getJSONObject("data").getString("channelId")
        runCatching { api.call("POST", "channel/join", JSONObject().put("channelId", id)) }
        repeat(60) { i ->
            val text = if (i % 7 == 0) "記録用のメッセージ $i\n2 行目 **太字** https://example.com" else "記録用のメッセージ $i"
            api.call("POST", "message/send", JSONObject().put("channelId", id).put("message", text).put("fileIds", JSONArray()))
        }
        channelId = id
        return id
    }

    /** 起動直後に出る画面 (ログイン・ホーム・前回開いていたチャンネルのどれか) を 1 回の待ちで見分ける (#31) */
    private val FIRST_SCREEN = By.res(java.util.regex.Pattern.compile("login\\.server_url|screen\\.home|screen\\.channel"))

    /** ログイン画面ならログインする (記録の最初の 1 回だけ通る)。どの画面でもなければ失敗にする */
    fun loginIfNeeded(device: UiDevice) {
        device.wait(Until.findObject(FIRST_SCREEN), 20_000) ?: error("起動後に画面が出ない")
        val url = device.findObject(By.res("login.server_url")) ?: return
        url.text = server
        device.findObject(By.res("login.connect")).click()
        device.wait(Until.findObject(By.res("login.username")), 20_000)!!.text = adminUser
        device.findObject(By.res("login.password")).text = adminPass
        if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
        device.wait(Until.findObject(By.res("login.submit")), 5_000)!!.click()
        device.wait(Until.findObject(By.res("screen.home")), 30_000) ?: error("ログイン後にホーム画面が出ない")
    }

    /** よく使う流れ: (前回のチャンネル) → ホーム → チャンネルを開く → 履歴をスクロール → 戻る → 設定 → 戻る */
    fun typicalUse(device: UiDevice, channelId: String) {
        device.wait(Until.findObject(FIRST_SCREEN), 20_000) ?: error("画面が出ない")
        // 前回開いていたチャンネルが復元されていたら、その画面も記録に入れてからホームに戻る (以前はホームを 20 秒待ってから戻っていた)
        if (device.hasObject(By.res("screen.channel"))) {
            device.wait(Until.findObject(By.res("channel.messages")), 10_000)
            device.pressBack()
        }
        device.wait(Until.findObject(By.res("screen.home")), 10_000) ?: error("ホーム画面が出ない")
        val row = findScrolling(device, "home.channel:$channelId") ?: error("記録用チャンネルが一覧に無い")
        row.click()
        val list = device.wait(Until.findObject(By.res("channel.messages")), 15_000) ?: error("チャンネル画面が出ない")
        device.waitForIdle()
        repeat(3) { list.scroll(Direction.UP, 1f) }
        repeat(3) { list.scroll(Direction.DOWN, 1f) }
        device.pressBack()
        device.wait(Until.findObject(By.res("screen.home")), 10_000)
        device.findObject(By.res("home.settings"))?.click()
        device.wait(Until.findObject(By.res("screen.settings")), 10_000)
        device.pressBack()
        device.wait(Until.findObject(By.res("screen.home")), 10_000)
    }

    private fun findScrolling(device: UiDevice, res: String) = run {
        repeat(6) {
            device.wait(Until.findObject(By.res(res)), 2_000)?.let { return@run it }
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.8f)
        }
        device.findObject(By.res(res))
    }

    /** サーバー API の最小限のクライアント (e2e の ServerApi と同じ作り) */
    class Api(private val server: String) {
        private var token: String? = null
        fun signIn(u: String, p: String) { call("POST", "user/sign-in", JSONObject().put("username", u).put("password", p)) }
        fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
            val c = URL("${server.trimEnd('/')}/api/$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            token?.let { c.setRequestProperty("Cookie", "token=$it") }
            if (method != "GET") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.headerFields["Set-Cookie"]?.forEach { h -> Regex("""(?:^|;\s*)token=([^;]*)""").find(h)?.let { token = it.groupValues[1] } }
            if (code >= 400) throw IllegalStateException("$method $path -> $code: $text")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
