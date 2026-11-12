package com.giracle.android.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf

/** 画面の種類。Web 版のルート (/app/...) に対応する */
sealed interface Screen {
    data object Home : Screen
    data class Channel(val channelId: String, val messageId: String? = null) : Screen
    data class ChannelSettings(val channelId: String) : Screen
    data object Inbox : Screen
    data class Search(val channelId: String? = null, val userId: String? = null) : Screen
    data object Members : Screen
    data object ChannelBrowser : Screen
    data object Settings : Screen
    data object ManageServer : Screen
}

/**
 * 画面遷移の履歴。ライブラリを使わない小さな実装 (依存と初期化コストを減らす)。
 * 先頭は常に Home。
 */
@Stable
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun push(s: Screen) {
        // 同じチャンネルを重ねて開かない
        if (stack.last() == s) return
        if (s is Screen.Channel) stack.removeAll { it is Screen.Channel && it.channelId == s.channelId && it != s }
        stack.add(s)
    }

    /** チャンネルを開く。チャンネル間の移動は積み重ねず置き換える */
    fun openChannel(channelId: String, messageId: String? = null) {
        val target = Screen.Channel(channelId, messageId)
        val top = stack.last()
        if (top is Screen.Channel) stack[stack.lastIndex] = target else push(target)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset() {
        stack.clear()
        stack.add(Screen.Home)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
