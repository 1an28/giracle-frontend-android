package com.giracle.e2e

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * テストの準備と「相手側」の操作に使う、サーバー API の最小限のクライアント。
 * アプリのコードは使わない (配布版を外から確かめるため)。仕様は backend の src/components/ (giracle-backend) に合わせる。
 * [server] は Web 版と同じ配置 (`/api/...`) のホスト。
 */
class ServerApi(private val server: String) {
    private var token: String? = null
    lateinit var userId: String
        private set

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
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
        c.headerFields["Set-Cookie"]?.forEach { h ->
            Regex("""(?:^|;\s*)token=([^;]*)""").find(h)?.let { token = it.groupValues[1] }
        }
        if (code >= 400) throw IllegalStateException("$method $path -> $code: $text")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    fun signIn(username: String, password: String): String {
        val r = call("POST", "user/sign-in", JSONObject().put("username", username).put("password", password))
        userId = r.getJSONObject("data").getString("userId")
        return userId
    }

    fun signUp(username: String, password: String, inviteCode: String?) {
        call("PUT", "user/sign-up", JSONObject().put("username", username).put("password", password).apply { inviteCode?.let { put("inviteCode", it) } })
    }

    fun createInvite(code: String, maxUsage: Int) {
        call("PUT", "server/create-invite", JSONObject().put("inviteCode", code).put("maxUsage", maxUsage))
    }

    fun createChannel(name: String, description: String): String =
        call("PUT", "channel/create", JSONObject().put("channelName", name).put("description", description))
            .getJSONObject("data").getString("channelId")

    fun joinChannel(channelId: String) { call("POST", "channel/join", JSONObject().put("channelId", channelId)) }
    fun deleteChannel(channelId: String) { call("DELETE", "channel/delete", JSONObject().put("channelId", channelId)) }

    fun send(channelId: String, text: String) {
        call("POST", "message/send", JSONObject().put("channelId", channelId).put("message", text).put("fileIds", JSONArray()))
    }

    /** 新しい順の本文 */
    fun recentTexts(channelId: String): List<String> = recent(channelId).map { it.content }

    data class Msg(val id: String, val content: String, val files: List<Pair<String, String>>, val reactions: Map<String, Int>)

    /** 新しい順のメッセージ (添付は (ファイル ID, 種類)、リアクションは 絵文字コード → 数) */
    fun recent(channelId: String): List<Msg> {
        val r = call("POST", "channel/get-history/$channelId", JSONObject().put("fetchLength", 30).put("fetchDirection", "older"))
        val msgs = r.getJSONObject("data").getJSONArray("history")
        return (0 until msgs.length()).map { i ->
            val m = msgs.getJSONObject(i)
            val files = m.optJSONArray("MessageFileAttached") ?: JSONArray()
            val reactions = m.optJSONArray("reactionSummary") ?: JSONArray()
            Msg(
                id = m.getString("id"),
                content = m.optString("content"),
                files = (0 until files.length()).map { files.getJSONObject(it).let { f -> f.getString("id") to f.optString("type") } },
                reactions = (0 until reactions.length()).associate { reactions.getJSONObject(it).let { x -> x.getString("emojiCode") to x.optInt("count") } },
            )
        }
    }
}
