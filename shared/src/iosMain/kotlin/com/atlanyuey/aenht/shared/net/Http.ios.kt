package com.atlanyuey.aenht.shared.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun platformEngine(): HttpClientEngine = Darwin.create()

internal actual val platformName: String = "iOS"
