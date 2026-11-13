package com.atlanyuey.aenht.shared.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

/**
 * The app hands its OkHttp client to the shared code, so API requests and
 * image loading share one connection pool (and tests can answer requests).
 */
object AndroidHttp {
    var client: OkHttpClient? = null
}

internal actual fun platformEngine(): HttpClientEngine = OkHttp.create {
    AndroidHttp.client?.let { preconfigured = it }
}

internal actual val platformName: String = "Android"
