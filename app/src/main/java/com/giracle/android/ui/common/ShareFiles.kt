package com.giracle.android.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.giracle.android.AppGraph
import com.giracle.android.data.model.FileAttached
import com.giracle.android.data.net.ApiException
import com.giracle.android.data.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 添付を他のアプリへ共有する (#50)。ファイルの取得には認証の Cookie が要るので、URL のままでは渡せない。
 * いったんキャッシュの `shared/` に取ってきて、FileProvider の content:// を渡す。前に共有した分は、次の共有のときに消す。
 */
object ShareFiles {
    private const val DIR = "shared"

    fun authority(ctx: Context) = ctx.packageName + ".files"

    suspend fun share(ctx: Context, graph: AppGraph, files: List<FileAttached>) {
        if (files.isEmpty()) return
        val dir = File(ctx.cacheDir, DIR)
        val local = withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            dir.mkdirs()
            files.mapIndexed { i, f ->
                val res = graph.http.newCall(Request.Builder().url(graph.api.fileUrl(f.id)).build()).await()
                res.use {
                    if (!it.isSuccessful) throw ApiException(it.code, it.body.string())
                    // 同じ名前のファイルがあっても上書きしないよう、番号のフォルダに分ける
                    val out = File(File(dir, i.toString()).apply { mkdirs() }, safeName(f.actualFileName))
                    out.outputStream().use { o -> it.body.byteStream().copyTo(o) }
                    out
                }
            }
        }
        val uris = ArrayList(local.map { FileProvider.getUriForFile(ctx, authority(ctx), it) })
        val type = files.map { it.type }.distinct().singleOrNull() ?: "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** パスの区切りや空の名前を避ける */
    fun safeName(name: String): String = name.replace('/', '_').replace('\\', '_').trim().ifEmpty { "file" }.take(120)
}
