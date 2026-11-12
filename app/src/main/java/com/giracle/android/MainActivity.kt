package com.giracle.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.giracle.android.ui.Root

class MainActivity : ComponentActivity() {
    /** 通知から開かれたときの行き先 (channelId, messageId) */
    val pendingOpen = mutableStateOf<Pair<String, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val graph = (application as GiracleApp).graph
        setContent { Root(graph, pendingOpen) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val ch = intent?.getStringExtra(EXTRA_CHANNEL) ?: return
        pendingOpen.value = ch to intent.getStringExtra(EXTRA_MESSAGE)
    }

    companion object {
        const val EXTRA_CHANNEL = "channelId"
        const val EXTRA_MESSAGE = "messageId"
    }
}
