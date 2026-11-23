package com.muratkirazkaya.meiro.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Whether the phone has a network that reaches the internet (the mock's `st.online`): the default network and its
 * INTERNET capability, followed through ConnectivityManager. Screens show the mock's offline banner while it is false.
 */
object Online {
    private val _state = MutableStateFlow(true)
    val state: StateFlow<Boolean> = _state

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        _state.value = cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _state.value = true }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            override fun onLost(network: Network) { _state.value = cm.activeNetwork != null && cm.activeNetwork != network }
        })
    }

    /**
     * `networkOnline`: a 3 s TCP connection to music.youtube.com:443. It runs only after a resolve has already failed,
     * so a reachable network costs nothing extra.
     */
    suspend fun reachable(): Boolean = withContext(Dispatchers.IO) {
        try { Socket().use { it.connect(InetSocketAddress("music.youtube.com", 443), 3_000) }; true } catch (e: Exception) { false }
    }
}
