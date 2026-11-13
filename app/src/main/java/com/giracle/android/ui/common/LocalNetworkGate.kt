package com.giracle.android.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.giracle.android.data.net.LocalNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LAN 内のサーバーに接続する前に、Android 17 のローカルネットワーク権限を求める (#42、D-031)。
 * [ensure] は、権限が要らない・既にある・許可された、のいずれかなら true を返す。
 */
class LocalNetworkGate internal constructor(
    private val ctx: Context,
    private val request: (CompletableDeferred<Boolean>) -> Unit,
) {
    suspend fun ensure(host: String): Boolean {
        if (!LocalNetwork.required || LocalNetwork.granted(ctx)) return true
        val local = withContext(Dispatchers.IO) { LocalNetwork.isLocalHost(host) }
        if (!local) return true
        val answer = CompletableDeferred<Boolean>()
        request(answer)
        return answer.await()
    }
}

@Composable
fun rememberLocalNetworkGate(): LocalNetworkGate {
    val ctx = LocalContext.current
    val holder = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        holder[0]?.complete(ok)
        holder[0] = null
    }
    return remember(ctx) {
        LocalNetworkGate(ctx) { d ->
            holder[0]?.complete(false)
            holder[0] = d
            launcher.launch(LocalNetwork.PERMISSION)
        }
    }
}

/** アプリの設定画面 (権限を手で許可してもらう) */
fun openAppSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
