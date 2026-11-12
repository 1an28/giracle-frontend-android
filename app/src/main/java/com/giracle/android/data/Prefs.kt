package com.giracle.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.giracle.android.data.net.AppJson
import com.giracle.android.data.net.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** 端末内だけの表示・操作設定 (Web 版の ClientConfig に相当 + 独自項目) */
@Immutable
@Serializable
data class ClientSettings(
    /** "system" | "light" | "dark" */
    val theme: String = "system",
    /** true: Enter で送信 / false: 送信ボタンのみ (スマホでは改行を優先するのが既定) */
    val sendWithEnter: Boolean = false,
    /** メッセージ間の余白 0..6 (Web 版の messageGapLevel) */
    val messageGapLevel: Int = 0,
    /** URL プレビューの説明文の最大文字数 (Web 版の maxUrlPreviewTextLength) */
    val maxUrlPreviewTextLength: Int = 50,
    /** メンション・返信をアプリ内通知する (Web 版の notifyInbox) */
    val notifyInbox: Boolean = true,
    /** すべての新着をアプリ内通知する (Web 版の notifyAll) */
    val notifyAll: Boolean = false,
    /** バックグラウンドでも接続を保って通知を受ける (常駐通知が出る) */
    val keepConnection: Boolean = false,
    /** 画像を自動で読み込む (通信量節約用に OFF にできる) */
    val autoLoadImages: Boolean = true,
    /** 文字サイズの倍率 */
    val fontScale: Float = 1f,
)

/**
 * SharedPreferences ベースの保存先。
 * 起動を速くするため、DataStore ではなく同期読み出しできる SharedPreferences を使う (値は少量)。
 */
class Prefs(context: Context, name: String = "giracle") : TokenStore {
    private val sp: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = sp.getString(K_SERVER, null)
        set(v) = sp.edit().putString(K_SERVER, v).apply()

    var directBackend: Boolean
        get() = sp.getBoolean(K_DIRECT, false)
        set(v) = sp.edit().putBoolean(K_DIRECT, v).apply()

    @Volatile private var tokenCache: String? = sp.getString(K_TOKEN, null)

    override var token: String?
        get() = tokenCache
        set(v) {
            if (v == tokenCache) return
            tokenCache = v
            sp.edit().putString(K_TOKEN, v).apply()
        }

    var userId: String?
        get() = sp.getString(K_USER, null)
        set(v) = sp.edit().putString(K_USER, v).apply()

    var lastChannelId: String?
        get() = sp.getString(K_LAST_CHANNEL, null)
        set(v) = sp.edit().putString(K_LAST_CHANNEL, v).apply()

    /** UnifiedPush で登録した宛先 URL (解除時に使う) */
    var pushEndpoint: String?
        get() = sp.getString("pushEndpoint", null)
        set(v) = sp.edit().putString("pushEndpoint", v).apply()

    /** チャンネル一覧の並び順 (ユーザーが並べ替えたもの) */
    var channelOrder: List<String>
        get() = sp.getString(K_ORDER, null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = sp.edit().putString(K_ORDER, v.joinToString(",")).apply()

    private val _settings = MutableStateFlow(
        sp.getString(K_SETTINGS, null)?.let {
            runCatching { AppJson.decodeFromString(ClientSettings.serializer(), it) }.getOrNull()
        } ?: ClientSettings(),
    )
    val settings: StateFlow<ClientSettings> = _settings

    fun updateSettings(f: (ClientSettings) -> ClientSettings) {
        val next = f(_settings.value)
        _settings.value = next
        sp.edit().putString(K_SETTINGS, AppJson.encodeToString(ClientSettings.serializer(), next)).apply()
    }

    fun clearSession() {
        tokenCache = null
        sp.edit().remove(K_TOKEN).remove(K_USER).remove(K_LAST_CHANNEL).apply()
    }

    private companion object {
        const val K_SERVER = "serverUrl"
        const val K_DIRECT = "directBackend"
        const val K_TOKEN = "token"
        const val K_USER = "userId"
        const val K_LAST_CHANNEL = "lastChannel"
        const val K_ORDER = "channelOrder"
        const val K_SETTINGS = "clientSettings"
    }
}
