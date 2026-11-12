package com.giracle.e2e

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 「他のアプリからの共有」を真似るために、テストが作ったファイルを content:// で渡す最小限の提供元 (#4)。
 * ファイルはこのアプリのキャッシュの share/ に置く。読み取りの許可は共有のインテントで一時的に与える。
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val dir = File(context!!.cacheDir, "share")
        val f = File(dir, uri.lastPathSegment ?: error("no name"))
        require(f.canonicalPath.startsWith(dir.canonicalPath)) { "outside share dir" }
        return f
    }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "png" -> "image/png"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        /** AndroidManifest.xml の authorities と同じ */
        const val AUTHORITY = "com.giracle.e2e.share"
    }
}
