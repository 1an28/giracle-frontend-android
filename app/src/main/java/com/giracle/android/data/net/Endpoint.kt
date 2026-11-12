package com.giracle.android.data.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 接続先サーバーの URL 群。
 *
 * 本番構成では Web 版と同じホストに `/api/` (HTTP) と `/ws` (WebSocket) がリバースプロキシされる
 * (giracle-frontend-solid の vite.config.ts と同じ配置)。ユーザーは `https://chat.example.com`
 * のようにホストだけ入れればよい。開発時など、バックエンドへ直接つなぐ場合は [direct] を true にする。
 */
data class Endpoint(
    /** 入力されたサーバー URL (末尾スラッシュ無し、パス無し) */
    val origin: HttpUrl,
    /** true ならバックエンドに直接つなぐ (`/api` を付けない) */
    val direct: Boolean,
) {
    /** HTTP API のベース。末尾は `/` で終わる */
    val apiBase: HttpUrl =
        if (direct) origin.newBuilder().encodedPath("/").build()
        else origin.newBuilder().encodedPath("/api/").build()

    /** WebSocket の URL (ws:// または wss://) */
    val wsUrl: String = origin.newBuilder().encodedPath("/ws").build().toString()
        .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")

    /** WebSocket 接続時に送る Origin ヘッダ。サーバーの CORS_ORIGIN に一致する必要がある */
    val originHeader: String = buildString {
        append(origin.scheme).append("://").append(origin.host)
        val defaultPort = HttpUrl.defaultPort(origin.scheme)
        if (origin.port != defaultPort) append(':').append(origin.port)
    }

    fun api(path: String): HttpUrl.Builder =
        apiBase.newBuilder().addPathSegments(path.trimStart('/'))

    /** 画像などの直接 URL */
    fun apiUrl(path: String): String = api(path).build().toString()

    companion object {
        /**
         * ユーザー入力から [Endpoint] を作る。スキーム省略時は https。
         * `.../api` で終わる入力は Flutter 版の保存形式と同じなので、`/api` を取り除いて扱う。
         */
        fun parse(input: String, direct: Boolean = false): Endpoint? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            if (s.endsWith("/api")) s = s.removeSuffix("/api")
            val url = s.toHttpUrlOrNull() ?: return null
            return Endpoint(url.newBuilder().encodedPath("/").query(null).build(), direct)
        }
    }
}
