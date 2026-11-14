package com.atlanyuey.aenht

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.atlanyuey.aenht.core.AppLanguage
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.Auth
import com.atlanyuey.aenht.core.Downloads
import com.atlanyuey.aenht.core.HistoryStore
import com.atlanyuey.aenht.core.L10n
import com.atlanyuey.aenht.core.Net
import com.atlanyuey.aenht.core.TagStore
import okio.Path.Companion.toOkioPath

class App : Application(), SingletonImageLoader.Factory {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        L10n.context = this
        AppSettings.init(this)
        Auth.init(this)
        HistoryStore.init(this)
        Downloads.init(this)
        TagStore.init(this)
        com.atlanyuey.aenht.core.EhAuth.init(this)
        com.atlanyuey.aenht.core.EhSession.refresh()
        com.atlanyuey.aenht.core.EhPages.init(this)
        com.atlanyuey.aenht.core.FavoritesStore.init(this)
        com.atlanyuey.aenht.core.TranslationService.init(this)
        com.atlanyuey.aenht.core.WebDavService.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Net.client }))
                add(com.atlanyuey.aenht.core.EhPageFetcher.Factory())
                add(com.atlanyuey.aenht.core.EhPageKeyer())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(600L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()
}
