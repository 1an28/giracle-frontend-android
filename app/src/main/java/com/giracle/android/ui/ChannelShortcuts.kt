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

    fun reportSent(context: Context, channelId: String, channelName: String?) {
        runCatching {
            val label = channelName?.let { "#$it" } ?: "チャンネル"
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_CHANNEL, channelId)
            val info = ShortcutInfoCompat.Builder(context, PREFIX + channelId)
                .setShortLabel(label.take(25))
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .setCategories(setOf(SHARE_CATEGORY))
                .setLongLived(true)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, info)
        }
    }

    /** ログアウトしたら消す (別のアカウントのチャンネルを出さないため) */
    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }
}
