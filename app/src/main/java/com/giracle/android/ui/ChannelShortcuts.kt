package com.giracle.android.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.giracle.android.MainActivity
import com.giracle.android.R

/**
 * 最近送ったチャンネルを、端末のショートカットとして登録する (#25)。
 * - 共有シートの上段に、直接の送り先として出る (res/xml/shortcuts.xml の share-target)
 * - ランチャーのアイコンの長押しメニューから、そのチャンネルを直接開ける
 * 登録は送信したときだけ行う (チャンネルを開くたびに行うと重いため)。
 */
object ChannelShortcuts {
    private const val PREFIX = "ch:"
    const val SHARE_CATEGORY = "com.giracle.android.category.SHARE_TARGET"

    fun channelIdOf(shortcutId: String?): String? = shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun shortcutId(channelId: String) = PREFIX + channelId

    fun reportSent(context: Context, channelId: String, channelName: String?) = publish(context, channelId, channelName)

    /** チャンネルのショートカットを登録・更新する。通知を「会話」として出すのにも使う (#26) */
    fun publish(context: Context, channelId: String, channelName: String?) {
        // 同じ名前で登録済みなら、もう一度登録しない (通知のたびに呼ばれるため)
        synchronized(published) { if (published[channelId] == channelName) return }
        runCatching {
            val label = channelName?.let { "#$it" } ?: "チャンネル"
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_CHANNEL, channelId)
            val info = ShortcutInfoCompat.Builder(context, shortcutId(channelId))
                .setShortLabel(label.take(25))
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .setCategories(setOf(SHARE_CATEGORY))
                .setLongLived(true)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, info)
            synchronized(published) { published[channelId] = channelName }
        }
    }

    /** ログアウトしたら消す (別のアカウントのチャンネルを出さないため) */
    private val published = HashMap<String, String?>()

    fun clear(context: Context) {
        synchronized(published) { published.clear() }
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }
}
