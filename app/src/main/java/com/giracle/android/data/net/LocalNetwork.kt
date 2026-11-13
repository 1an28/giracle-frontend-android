package com.giracle.android.data.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android 17 のローカルネットワーク権限 (#42、D-031)。
 *
 * targetSdk 37 以上のアプリは、LAN のアドレスへの通信に `ACCESS_LOCAL_NETWORK` (権限グループ「付近のデバイス」) が要る。
 * 権限が無いと TCP はタイムアウトになる。セルフホストのサーバーを自宅 LAN に置く人のために、
 * 接続先が LAN のときだけ権限を求める (インターネット上のサーバーしか使わない人には尋ねない)。
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** 権限が要る OS か (Android 17 = API 37 以上) */
    val required: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun granted(ctx: Context): Boolean =
        !required || ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 名前だけで LAN と分かるもの (`.local` などの mDNS・家庭内向けの名前、点の無い名前) */
    fun isLocalName(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost")) return false // 端末自身 (ループバック) は対象外
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
        return !h.contains('.') && !h.contains(':')
    }

    /** アドレスが LAN か (プライベート IPv4・リンクローカル・IPv6 ULA)。ループバックは対象外 */
    fun isLocalAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress) return false
        if (a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        if (a is Inet6Address) return (a.address[0].toInt() and 0xfe) == 0xfc // fc00::/7 (ULA)
        return false
    }

    /**
     * 接続先が LAN か。名前は解決して確かめる (家庭内の DNS が LAN のアドレスを返す場合があるため)。
     * 解決できないときは false (権限を求めず、通常の接続失敗として扱う)。通信するのでメインスレッドで呼ばない。
     */
    fun isLocalHost(host: String): Boolean {
        if (isLocalName(host)) return true
        return runCatching { InetAddress.getAllByName(host).any(::isLocalAddress) }.getOrDefault(false)
    }

    /** 通信せずに分かる範囲で LAN か (名前・IP アドレスの直書き)。画面の表示の判断に使う */
    fun looksLocal(host: String): Boolean {
        if (isLocalName(host)) return true
        val literal = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host.contains(':')
        return literal && runCatching { isLocalAddress(InetAddress.getByName(host)) }.getOrDefault(false)
    }

    /** 接続先が LAN で、権限が要るのにまだ無いか */
    fun needsPermission(ctx: Context, host: String): Boolean =
        required && !granted(ctx) && isLocalHost(host)

    const val DENIED_MESSAGE =
        "LAN 内のサーバーに接続するには「付近のデバイス」の権限が必要です (Android 17 以降)。アプリの設定で許可してください。"
}
