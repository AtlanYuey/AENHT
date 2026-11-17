package com.atlanyuey.aenht.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether the device is online. [restored] counts the times the network
 * came back: what failed meanwhile (pictures, lists, pages, downloads) is
 * tried again on its own, without a tap on Retry.
 */
object Connectivity {
    var restored by mutableIntStateOf(0)
        private set

    @Volatile private var online = true

    fun init(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        online = manager.activeNetwork != null
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = changed(true)
                override fun onLost(network: Network) = changed(false)
            })
        }
    }

    private fun changed(now: Boolean) {
        val was = online
        online = now
        if (now && !was) AppScope.launch {
            // A moment for the new connection (its addresses) before trying again.
            delay(1000)
            if (online) restored++
        }
    }
}

/** While this shows (an error with a retry button), [retry] runs when the network comes back. */
@Composable
fun RetryWhenOnline(retry: () -> Unit) {
    val restored = Connectivity.restored
    val shown = remember { restored }
    LaunchedEffect(restored) { if (restored != shown) retry() }
}
