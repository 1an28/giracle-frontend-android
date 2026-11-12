package com.giracle.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 他のアプリの「共有」から受け取ったもの (D-020) */
@Immutable
data class SharedContent(val uris: List<Uri>, val text: String?) {
    val description: String
        get() = buildList {
            if (uris.isNotEmpty()) add("ファイル ${uris.size} 件")
            if (!text.isNullOrBlank()) add("文章")
        }.joinToString("と")
}

/**
 * 共有で受け取り、まだ送り先のチャンネルが決まっていないもの。
 * ホーム画面でチャンネルを選ぶと、そのチャンネルの入力欄が受け取って (添付・下書きにして) 空にする。
 */
object PendingShare {
    var content by mutableStateOf<SharedContent?>(null)

    /** ホーム画面で選ばれた送り先のチャンネル。このチャンネルの入力欄だけが受け取る */
    var target by mutableStateOf<String?>(null)

    /** 共有のインテントなら受け取って true を返す */
    fun accept(intent: Intent?): Boolean {
        intent ?: return false
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList(Intent.EXTRA_STREAM)
            else -> return false
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (uris.isEmpty() && text.isNullOrBlank()) return false
        target = null
        content = SharedContent(uris, text)
        return true
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelable(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key) as? Uri

    @Suppress("DEPRECATION")
    private fun Intent.parcelableList(key: String): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java) else getParcelableArrayListExtra<Uri>(key))
            ?.filterNotNull() ?: emptyList()
}
