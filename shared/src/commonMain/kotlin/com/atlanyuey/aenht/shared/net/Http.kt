package com.atlanyuey.aenht.shared.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

/** The platform's HTTP engine (OkHttp on Android and JVM, URLSession on iOS). */
internal expect fun platformEngine(): HttpClientEngine

/** "Android" or "iOS", for the User-Agent. */
internal expect val platformName: String

object AppInfo {
    /** Set by the app at start (versionName / CFBundleShortVersionString). */
    var version: String = "0.1"

    val userAgent: String
        get() = "AENHT/$version ($platformName; +https://github.com/AtlanYuey/AENHT)"
}

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/** Builds the client used for API requests; tests pass a mock engine. */
internal fun buildHttpClient(engine: HttpClientEngine = platformEngine()): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 20_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    defaultRequest {
        headers.append(HttpHeaders.UserAgent, AppInfo.userAgent)
    }
}
