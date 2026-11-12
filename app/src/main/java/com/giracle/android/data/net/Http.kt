package com.giracle.android.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** アプリ全体で 1 つだけ使う JSON 設定 */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = false
}

/** サーバーがエラーを返したとき。[body] はサーバーの文言 (例: "Invalid token") */
class ApiException(val code: Int, val body: String) : IOException("HTTP $code: $body") {
    val isUnauthorized: Boolean get() = code == 401
}

/**
 * セッショントークンの保持役。
 * バックエンドは Cookie `token` で認証するが、Cookie Jar を使わず自前で付ける。
 * (WebSocket・画像読み込みにも同じヘッダを付けたいので、1 か所で管理する方が単純で速い)
 */
interface TokenStore {
    var token: String?
}

/** すべてのリクエストに Cookie を付け、Set-Cookie で更新されたトークンを保存する */
class TokenInterceptor(private val store: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = store.token
        val req = if (token != null && chain.request().header("Cookie") == null) {
            chain.request().newBuilder().header("Cookie", "token=$token").build()
        } else chain.request()
        val res = chain.proceed(req)
        for (h in res.headers("Set-Cookie")) {
            if (!h.startsWith("token=")) continue
            val value = h.substringAfter("token=").substringBefore(';')
            // 期限切れ (削除) 指定なら消す
            val expired = h.contains("Max-Age=0", ignoreCase = true) ||
                h.contains("Expires=Thu, 01 Jan 1970", ignoreCase = true)
            store.token = if (value.isEmpty() || expired) null else value
        }
        return res
    }
}

fun buildOkHttp(store: TokenStore, cacheDir: java.io.File?): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // WebSocket の死活監視は OkHttp の ping に任せる (サーバーの ping シグナルとは別)
        .pingInterval(25, TimeUnit.SECONDS)
        .addInterceptor(TokenInterceptor(store))
        .apply { if (cacheDir != null) cache(okhttp3.Cache(cacheDir, 64L * 1024 * 1024)) }
        .build()

/** OkHttp の Call をコルーチンで待つ。キャンセルされたら Call も止める */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** リクエストを送り、本文を文字列で返す。2xx 以外は [ApiException] */
suspend fun OkHttpClient.fetchString(request: Request): String {
    val res = newCall(request).await()
    return withContext(Dispatchers.IO) {
        res.use {
            val body = it.body.string()
            if (!it.isSuccessful) throw ApiException(it.code, body)
            body
        }
    }
}
