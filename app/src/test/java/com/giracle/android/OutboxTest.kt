package com.giracle.android

import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.Outbox
import com.giracle.android.data.OutboxItem
import com.giracle.android.data.model.FileAttached
import com.giracle.android.data.model.Message
import com.giracle.android.data.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    private fun newOutbox(name: String) = Outbox(File(dir, name).also { it.delete(); File(it.path + ".used").delete() })

    private class FakeSender(var fail: Throwable? = null, var recentMessages: List<Message> = emptyList()) : Outbox.Sender {
        val sent = mutableListOf<String>()
        override suspend fun send(item: OutboxItem): String {
            fail?.let { throw it }
            sent += item.text
            return "sent-${item.localId}"
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
        fun msg(user: String, text: String, at: Long, id: String = "m", files: List<String> = emptyList(), reply: String? = null) = Message(
            id = id, channelId = "c", userId = user, content = text, createdAt = Instant.ofEpochMilli(at).toString(),
            replyingMessageId = reply, files = files.map { FileAttached(id = it) },
        )
        fun delivered(i: OutboxItem, vararg m: Message, used: Set<String> = emptySet()) = Outbox.findDelivered(i, m.toList(), "me", used) != null
        assertTrue(delivered(item, msg("me", "hi", now)))
        assertFalse("他の人", delivered(item, msg("you", "hi", now)))
        assertFalse("本文が違う", delivered(item, msg("me", "ho", now)))
        assertFalse("ずっと前の同じ本文", delivered(item, msg("me", "hi", now - 3_600_000)))

        // #66 の 1: 本文なしの画像。直前に送った別の画像 (本文も "") と取り違えない
        val image = OutboxItem("l2", "c", "", fileIds = listOf("f2"), createdAt = now)
        assertFalse("別の画像", delivered(image, msg("me", "", now, files = listOf("f1"))))
        assertTrue("同じ添付", delivered(image, msg("me", "", now, files = listOf("f2"))))
        assertTrue("添付の ID で決まるので時刻は見ない", delivered(image, msg("me", "", now - 3_600_000, files = listOf("f2"))))
        assertFalse("添付の組が違う", delivered(image, msg("me", "", now, files = listOf("f2", "f3"))))
        assertFalse("添付の無い送信待ちは、添付付きのメッセージに当てない", delivered(OutboxItem("l3", "c", "", createdAt = now), msg("me", "", now, files = listOf("f1"))))

        // 返信先も一致が条件
        val reply = item.copy(replyTo = "r1")
        assertFalse("返信先が違う", delivered(reply, msg("me", "hi", now, reply = "r2")))
        assertFalse("返信でないものに当てない", delivered(reply, msg("me", "hi", now)))
        assertFalse("返信に当てない", delivered(item, msg("me", "hi", now, reply = "r1")))
        assertTrue(delivered(reply, msg("me", "hi", now, reply = "r1")))

        // #66 の 3: 一致に使ったものは使わない
        assertFalse("使い切った", delivered(item, msg("me", "hi", now, id = "a"), used = setOf("a")))
        assertTrue("使っていない方に当たる", delivered(item, msg("me", "hi", now, id = "a"), msg("me", "hi", now, id = "b"), used = setOf("a")))
    }

    /** #66 の 2・3: 同じ本文を 2 回送信待ちにして、届いていたのが 1 件だけなら、もう 1 件は送る */
    @Test fun oneDeliveredMessageMatchesOnlyOneItem() = runBlocking {
        val ob = newOutbox("ob5.json")
        val now = System.currentTimeMillis()
        ob.add("c", "了解", emptyList(), null, now = now)
        ob.add("c", "了解", emptyList(), null, now = now + 1)
        val delivered = Message(id = "m", channelId = "c", userId = "me", content = "了解", createdAt = Instant.ofEpochMilli(now + 1000).toString())
        val s = FakeSender(recentMessages = listOf(delivered))
        assertEquals(2, ob.flush(s))
        assertEquals(listOf("了解"), s.sent)
        assertTrue(ob.items.value.isEmpty())
    }

    /**
     * レビューの指摘: 1 件目を送った直後に電波が切れ、次の送り直しで 1 件目の送信が履歴に入っていても、
     * 同じ本文の 2 件目をそれに当てない (送り直しをまたいで、作り直しても)
     */
    @Test fun sentMessageIsNotMatchedAgainAcrossFlushes() = runBlocking {
        val f = File(dir, "ob6.json").also { it.delete(); File(it.path + ".used").delete() }
        val ob = Outbox(f)
        val now = System.currentTimeMillis()
        val a = ob.add("c", "了解", emptyList(), null, now = now)
        ob.add("c", "了解", emptyList(), null, now = now + 1)
        var calls = 0
        val first = object : Outbox.Sender {
            override suspend fun send(item: OutboxItem): String {
                if (calls++ > 0) throw IOException("offline again")
                return "m1"
            }
            override suspend fun recent(channelId: String) = emptyList<Message>()
            override val myUserId = "me"
        }
        assertEquals(1, ob.flush(first))
        assertEquals(listOf("了解"), ob.items.value.map { it.text })
        assertFalse(ob.items.value.any { it.localId == a.localId })

        val m1 = Message(id = "m1", channelId = "c", userId = "me", content = "了解", createdAt = Instant.ofEpochMilli(now + 1000).toString())
        val s = FakeSender(recentMessages = listOf(m1))
        assertEquals(1, Outbox(f).flush(s)) // 作り直しても覚えている
        assertEquals("2 件目は送る", listOf("了解"), s.sent)
    }

    /** #67: 多数のスレッドから同時に add・remove しても、ファイルが壊れず、読み直した件数が合う */
    @Test fun concurrentAddAndRemoveKeepFileConsistent() = runBlocking {
        val f = File(dir, "ob5.json").also { it.delete() }
        val ob = Outbox(f)
        val keep = 200
        val drop = 100
        val added = (0 until keep + drop).map { i ->
            async(Dispatchers.IO) { ob.add("c", "m$i", emptyList(), null) }
        }.awaitAll()
        added.takeLast(drop).map { item -> async(Dispatchers.IO) { ob.remove(item.localId) } }.awaitAll()
        assertEquals(keep, ob.items.value.size)
        val reloaded = Outbox(f).items.value
        assertEquals("読み直した件数", keep, reloaded.size)
        assertEquals(ob.items.value.map { it.localId }.toSet(), reloaded.map { it.localId }.toSet())
        assertFalse("一時ファイルが残っていない", File(f.path + ".tmp").exists())
    }

    @Test fun clearRemovesFile() = runBlocking {
        val f = File(dir, "ob6.json").also { it.delete() }
        val ob = Outbox(f)
        ob.add("c", "1", emptyList(), null)
        assertTrue(f.exists())
        ob.clear()
        assertFalse(f.exists())
        assertTrue(Outbox(f).items.value.isEmpty())
    }

    @Test fun networkFailureClassification() {
        assertTrue(Outbox.isNetworkFailure(IOException()))
        assertTrue(Outbox.isNetworkFailure(java.net.SocketTimeoutException()))
        assertFalse(Outbox.isNetworkFailure(ApiException(400, "bad")))
        assertFalse(Outbox.isNetworkFailure(IllegalStateException()))
    }
}
