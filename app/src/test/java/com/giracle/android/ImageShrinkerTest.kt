package com.giracle.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.ImageShrinker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 送る前の写真の縮小 (#28)。Robolectric の実際の画像処理 (NATIVE) で確かめる */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageShrinkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun write(bmp: Bitmap, format: Bitmap.CompressFormat, name: String): Pair<Uri, Long> {
        val f = File(ctx.cacheDir, name)
        f.outputStream().use { bmp.compress(format, 100, it) }
        return Uri.fromFile(f) to f.length()
    }

    /** 縮小の効果が分かるよう、細かい模様の画像を作る */
    private fun noisy(w: Int, h: Int, alpha: Boolean = false): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(1)
        val px = IntArray(w * h) { if (alpha && it % 7 == 0) Color.TRANSPARENT else (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    @Test fun bigJpegIsShrunkToMaxEdge() {
        val (uri, size) = write(noisy(3000, 2000), Bitmap.CompressFormat.JPEG, "big.jpg")
        val r = ImageShrinker.shrink(ctx.contentResolver, uri, "image/jpeg", size)
        assertNotNull(r)
        assertEquals(ImageShrinker.MAX_EDGE, maxOf(r!!.width, r.height))
        assertTrue("縮めた方が小さい", r.bytes.size < size)
        val decoded = BitmapFactory.decodeByteArray(r.bytes, 0, r.bytes.size)
        assertEquals(2048, decoded.width)
        assertEquals(1365, decoded.height)
    }

    @Test fun smallPngIsKept() {
        val (uri, size) = write(noisy(800, 600), Bitmap.CompressFormat.PNG, "small.png")
        assertNull(ImageShrinker.shrink(ctx.contentResolver, uri, "image/png", size))
    }

    @Test fun transparentPngIsKept() {
        val (uri, size) = write(noisy(3000, 2000, alpha = true), Bitmap.CompressFormat.PNG, "alpha.png")
        assertNull(ImageShrinker.shrink(ctx.contentResolver, uri, "image/png", size))
    }

    @Test fun gifAndWebpAreNotTargets() {
        assertTrue(!ImageShrinker.isTarget("image/gif"))
        assertTrue(!ImageShrinker.isTarget("image/webp"))
        assertTrue(ImageShrinker.isTarget("image/HEIC"))
    }

    @Test fun names() {
        assertEquals("photo.jpg", ImageShrinker.jpegName("photo.heic"))
        assertEquals("noext.jpg", ImageShrinker.jpegName("noext"))
    }
}
