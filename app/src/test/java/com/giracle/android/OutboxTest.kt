package com.giracle.android

import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.Outbox
import com.giracle.android.data.OutboxItem
import com.giracle.android.data.model.Message
import com.giracle.android.data.net.ApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant

/** 電波が無いときの送信待ち (#30) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutboxTest {
    private val dir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
    private fun newOutbox(name: String) = Outbox(File(dir, name).also { it.delete() })

    private class FakeSender(var fail: Throwable? = null, var recentMessages: List<Message> = emptyList()) : Outbox.Sender {
        val sent = mutableListOf<String>()
        override suspend fun send(item: OutboxItem) {
            fail?.let { throw it }
            sent += item.text
        }
        override suspend fun recent(channelId: String) = recentMessages
        override val myUserId = "me"
    }

    @Test fun sendsInOrderAndKeepsAcrossRestart() = runBlocking {
        val f = File(dir, "ob1.json").also { it.delete() }
        Outbox(f).apply { add("c", "1", emptyList(), null); add("c", "2", emptyList(), null) }
        val ob = Outbox(f) // 作り直しても残っている (アプリを閉じても残る)
        assertEquals(2, ob.items.value.size)
        val s = FakeSender()
        assertEquals(2, ob.flush(s))
        assertEquals(listOf("1", "2"), s.sent)
        assertTrue(ob.items.value.isEmpty())
        assertTrue(Outbox(f).items.value.isEmpty())
    }

    @Test fun stopsWhileOffline() = runBlocking {
        val ob = newOutbox("ob2.json")
        ob.add("c", "1", emptyList(), null); ob.add("c", "2", emptyList(), null)
        val s = FakeSender(fail = IOException("offline"))
        assertEquals(0, ob.flush(s))
        assertEquals(2, ob.items.value.size)
        assertTrue(ob.items.value.all { it.error == null })
    }

    @Test fun serverRejectionIsKeptWithReasonAndNotRetried() = runBlocking {
        val ob = newOutbox("ob3.json")
        val item = ob.add("c", "1", emptyList(), null)
        assertEquals(0, ob.flush(FakeSender(fail = ApiException(404, "Channel not found"))))
        assertNotNull(ob.items.value.single().error)
        // 自動の送り直しでは送らない
        val ok = FakeSender()
        assertEquals(0, ob.flush(ok))
        assertTrue(ok.sent.isEmpty())
        // 利用者が「今すぐ送る」を選べば送る
        assertEquals(1, ob.flush(ok, only = item.localId))
        assertEquals(listOf("1"), ok.sent)
    }

    @Test fun skipsWhenAlreadyDelivered() = runBlocking {
        val ob = newOutbox("ob4.json")
        val now = System.currentTimeMillis()
        ob.add("c", "hello", emptyList(), null, now = now)
        val delivered = Message(id = "m", channelId = "c", userId = "me", content = "hello", createdAt = Instant.ofEpochMilli(now + 1000).toString())
        val s = FakeSender(recentMessages = listOf(delivered))
        assertEquals(1, ob.flush(s))
        assertTrue("届いていたので送らない", s.sent.isEmpty())
        assertTrue(ob.items.value.isEmpty())
    }

    @Test fun alreadyDeliveredRules() {
        val now = 1_700_000_000_000L
        val item = OutboxItem("l", "c", "hi", createdAt = now)
        fun msg(user: String, text: String, at: Long) = Message(id = "m", channelId = "c", userId = user, content = text, createdAt = Instant.ofEpochMilli(at).toString())
        assertTrue(Outbox.alreadyDelivered(item, listOf(msg("me", "hi", now)), "me"))
        assertFalse("他の人", Outbox.alreadyDelivered(item, listOf(msg("you", "hi", now)), "me"))
        assertFalse("本文が違う", Outbox.alreadyDelivered(item, listOf(msg("me", "ho", now)), "me"))
        assertFalse("ずっと前の同じ本文", Outbox.alreadyDelivered(item, listOf(msg("me", "hi", now - 3_600_000)), "me"))
    }

    @Test fun networkFailureClassification() {
        assertTrue(Outbox.isNetworkFailure(IOException()))
        assertTrue(Outbox.isNetworkFailure(java.net.SocketTimeoutException()))
        assertFalse(Outbox.isNetworkFailure(ApiException(400, "bad")))
        assertFalse(Outbox.isNetworkFailure(IllegalStateException()))
    }
}
