package com.giracle.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.giracle.android.data.Emojis
import com.giracle.android.notify.Notifier

class GiracleApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // 絵文字表は裏で読む (最初のリアクション表示までに間に合えばよい)
        Emojis.preload(this, graph.scope)
        Notifier.init(this, graph)

        // 前面・背面の切り替えで WebSocket を管理する
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                graph.store.appInForeground = true
                graph.ws.nudge()
            }

            override fun onStop(owner: LifecycleOwner) {
                graph.store.appInForeground = false
                graph.saveSnapshot()
                // 常時接続が OFF なら、背面に回ったら切断して電池を節約する
                if (!graph.prefs.settings.value.keepConnection) graph.ws.stop()
            }
        })
    }

    /** 画像読み込みは API と同じ OkHttpClient を使う (認証 Cookie・接続プールを共有) */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.http }))
                add(AnimatedImageDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("img")).maxSizeBytes(256L * 1024 * 1024).build() }
            .build()
}
