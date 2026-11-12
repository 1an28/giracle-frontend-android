package com.giracle.android.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 送る前に写真を縮小する (#28、D-025)。
 * - 対象は JPEG・HEIC/HEIF と、透過の無い PNG (スクリーンショットなど)。GIF・WebP・動画は縮めない
 *   (GIF・WebP はアニメーションの場合があるため)
 * - 長辺 [MAX_EDGE]px・JPEG 品質 [QUALITY] にする。Exif の向きは反映し、位置情報などのメタデータは落ちる
 *   (Bitmap から作り直すため)
 * - 縮めても元より小さくならないときは null を返す (元のまま送る)
 */
object ImageShrinker {
    const val MAX_EDGE = 2048
    const val QUALITY = 85

    private val TARGETS = setOf("image/jpeg", "image/jpg", "image/heic", "image/heif", "image/png")

    fun isTarget(mime: String) = mime.lowercase() in TARGETS

    class Result(val bytes: ByteArray, val width: Int, val height: Int)

    /** 縮小した JPEG を返す。対象外・失敗・小さくならないときは null */
    fun shrink(resolver: ContentResolver, uri: Uri, mime: String, originalSize: Long): Result? {
        if (!isTarget(mime)) return null
        val bmp = runCatching { decode(resolver, uri) }.getOrNull() ?: return null
        try {
            // 透過のある PNG は JPEG にすると背景が黒くなるので、そのまま送る
            if (mime.lowercase() == "image/png" && bmp.hasAlpha() && hasTransparentPixel(bmp)) return null
            // PNG は、長辺が上限以下ならそのまま送る (文字のくっきりしたスクリーンショットを JPEG にしない)
            if (mime.lowercase() == "image/png" && max(bmp.width, bmp.height) <= MAX_EDGE) return null
            val out = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
            val bytes = out.toByteArray()
            if (originalSize in 1..bytes.size.toLong()) return null
            return Result(bytes, bmp.width, bmp.height)
        } finally {
            bmp.recycle()
        }
    }

    /** 縮小後の名前 (拡張子を .jpg にする) */
    fun jpegName(name: String): String = name.substringBeforeLast('.', name) + ".jpg"

    /** 長辺 [MAX_EDGE] 以下に縮めて読み込む (向きは Exif に合わせる) */
    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder は Exif の向きを自動で反映し、HEIC も読める。失敗したら BitmapFactory で読む
            // (JVM のテスト環境の Robolectric は ImageDecoder に対応していないため、テストはこちらを通る)
            runCatching {
                val src = ImageDecoder.createSource(resolver, uri)
                return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    val (w, h) = info.size.width to info.size.height
                    val scale = targetScale(w, h)
                    if (scale < 1f) dec.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        }
        return decodeWithBitmapFactory(resolver, uri)
    }

    private fun decodeWithBitmapFactory(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val raw = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = targetScale(raw.width, raw.height)
        val m = Matrix()
        if (scale < 1f) m.postScale(scale, scale)
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0
        if (rotation != 0) m.postRotate(rotation.toFloat())
        if (m.isIdentity) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    internal fun targetScale(w: Int, h: Int): Float {
        val edge = max(w, h)
        return if (edge <= MAX_EDGE) 1f else MAX_EDGE.toFloat() / edge
    }

    private fun exifRotation(e: ExifInterface): Int = when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    /** 実際に透けている画素があるか (hasAlpha だけでは、透過の無い PNG も true になることがあるため) */
    private fun hasTransparentPixel(b: Bitmap): Boolean {
        val stepX = max(1, b.width / 64)
        val stepY = max(1, b.height / 64)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                if ((b.getPixel(x, y) ushr 24) < 0xFF) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }
}
