package com.giracle.android.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 1 つのファイルへの書き込み・削除を 1 本にまとめる (#67、D-054)。
 * - 書き込みと削除はこのオブジェクトの中で順に行い、同時には走らない (同じ一時ファイルに 2 つのスレッドが書いて中身が混ざるのを防ぐ)
 * - 一時ファイルに書き、ディスクへ書き出し (fsync) てから名前を変える。途中で落ちても、前の内容か新しい内容のどちらかが残る
 * - 書く中身は、書く直前に (ほかの書き込みを待った後で) [write] の引数で作る。後から呼ばれた書き込みが古い中身で上書きしないため
 *
 * ファイルを読み書きするので、メインスレッドからは呼ばない。
 */
class SafeFile(val file: File) {
    private val tmp = File(file.path + ".tmp")

    @Synchronized
    fun readText(): String? = if (file.exists()) file.readText() else null

    /** [content] が null を返したら書かない。書けなければ [IOException] */
    @Synchronized
    fun write(content: () -> String?) {
        val text = content() ?: return
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("rename failed: $tmp -> $file")
    }

    @Synchronized
    fun delete() {
        file.delete()
        tmp.delete()
    }
}
