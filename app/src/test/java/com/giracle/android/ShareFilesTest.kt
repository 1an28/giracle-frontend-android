package com.giracle.android

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.net.UploadFile
import com.giracle.android.ui.common.ShareFiles
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 添付を他のアプリへ共有する (#50): 認証付きで取ってきたファイルを、content:// で渡せること。実サーバーが要る */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareFilesTest {
    private val server = System.getenv("GIRACLE_TEST_SERVER")

    @Test fun sharesUploadedFile() = runBlocking {
        assumeTrue("GIRACLE_TEST_SERVER 未指定のためスキップ", server != null)
        val app = ApplicationProvider.getApplicationContext<GiracleApp>()
        val graph = app.graph
        graph.setServer(server!!, false)
        graph.signIn(System.getenv("GIRACLE_TEST_ADMIN_USER") ?: "admin", System.getenv("GIRACLE_TEST_ADMIN_PASS") ?: "password123")
        val api = graph.api
        val ch = api.createChannel("share-${System.currentTimeMillis().toString().takeLast(6)}", "共有のテスト (自動で消す)").channelId
        try {
            runCatching { api.joinChannel(ch) }
            val bytes = "共有のテスト ${System.nanoTime()}".toByteArray()
            val fileId = api.uploadFile(ch, UploadFile("memo/1.txt", "text/plain", bytes.toRequestBody("text/plain".toMediaType())))
            val msg = api.send(ch, "添付", listOf(fileId), null)

            ShareFiles.share(app, graph, msg.files)

            val chooser = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals(ShareFiles.authority(app), uri.authority)
            // 渡したファイルの中身が、送ったものと同じ
            val got = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(bytes, got)
        } finally {
            runCatching { api.deleteChannel(ch) }
        }
    }
}
