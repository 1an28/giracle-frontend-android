package com.giracle.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.giracle.android.ui.ChannelShortcuts
import com.giracle.android.ui.PendingShare
import com.giracle.android.ui.Root

class MainActivity : ComponentActivity() {
    /** 通知から開かれたときの行き先 (channelId, messageId) */
    val pendingOpen = mutableStateOf<Pair<String, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val graph = (application as GiracleApp).graph
        // サーバーからの読み込みをすぐ始める (前面への切り替えの通知を待たない。起動を速くするため)
        graph.ensureSessionLoaded()
        setContent { Root(graph, pendingOpen) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // 他のアプリの「共有」から開かれたとき (送り先のチャンネルはホーム画面で選んでもらう)
        if (PendingShare.accept(intent)) {
            // 共有シートで直接チャンネルが選ばれたとき (#25) は、ホームを経由せずにそのチャンネルを開く
            ChannelShortcuts.channelIdOf(intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID))?.let { ch ->
                PendingShare.target = ch
                pendingOpen.value = ch to null
            }
            return
        }
        val ch = intent?.getStringExtra(EXTRA_CHANNEL) ?: return
        pendingOpen.value = ch to intent.getStringExtra(EXTRA_MESSAGE)
    }

    companion object {
        const val EXTRA_CHANNEL = "channelId"
        const val EXTRA_MESSAGE = "messageId"
    }
}
