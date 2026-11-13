package com.atlanyuey.aenht.shared.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformEngine(): HttpClientEngine = OkHttp.create()

internal actual val platformName: String = "JVM"
