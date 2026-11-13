package com.giracle.android

import com.giracle.android.data.SafeFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** 保存を 1 本にまとめる (#67) */
class SafeFileTest {
    private val dir = Files.createTempDirectory("safefile").toFile()

    /** 同時に書いても中身が混ざらず、最後に残るのは書いた中身のどれか 1 つそのもの */
    @Test fun concurrentWritesNeverMix() {
        val sf = SafeFile(File(dir, "a.json"))
        val start = CountDownLatch(1)
        val bodies = (0 until 16).map { i -> "[" + "\"${'a' + i}\",".repeat(5_000) + "\"end\"]" }
        val threads = bodies.map { body ->
            thread { start.await(); repeat(20) { sf.write { body } } }
        }
        start.countDown()
        threads.forEach { it.join() }
        val text = sf.readText()
        assert(text in bodies) { "中身が混ざった: ${text?.take(80)}" }
        assertFalse(File(dir, "a.json.tmp").exists())
    }

    /** 書く中身は、ほかの書き込みを待った後で作る (後から呼んだ書き込みが古い中身で上書きしない) */
    @Test fun contentIsProducedUnderLock() {
        val sf = SafeFile(File(dir, "b.json"))
        val counter = AtomicInteger()
        val threads = (0 until 8).map { thread { repeat(50) { sf.write { counter.incrementAndGet().toString() } } } }
        threads.forEach { it.join() }
        assertEquals("400", sf.readText())
    }

    @Test fun nullSkipsAndDeleteRemoves() {
        val sf = SafeFile(File(dir, "c.json"))
        sf.write { null }
        assertNull(sf.readText())
        sf.write { "x" }
        assertEquals("x", sf.readText())
        sf.delete()
        assertNull(sf.readText())
    }
}
