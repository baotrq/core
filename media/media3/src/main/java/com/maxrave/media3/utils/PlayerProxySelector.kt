package com.maxrave.media3.utils

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The proxy every player request goes through, read again for each new connection.
 *
 * It used to be a fixed `Proxy` handed to OkHttp when the media source factory was built, so
 * turning the proxy off in Settings only reached the player after the app was restarted. With a
 * selector, OkHttp asks again on every connection, so the change applies to the next request.
 */
object PlayerProxySelector : ProxySelector() {
    @Volatile
    private var current: Proxy? = null

    private val watching = AtomicBoolean(false)

    /** Takes the stored setting now, then follows it. Only the first call starts watching. */
    fun watch(
        dataStoreManager: DataStoreManager,
        scope: CoroutineScope,
    ) {
        if (!watching.compareAndSet(false, true)) return
        current = dataStoreManager.getJVMProxy()?.let { toProxy(it.type, it.host, it.port) }
        scope.launch {
            combine(
                dataStoreManager.usingProxy,
                dataStoreManager.proxyType,
                dataStoreManager.proxyHost,
                dataStoreManager.proxyPort,
            ) { using, type, host, port ->
                if (using == DataStoreManager.TRUE) toProxy(type, host, port) else null
            }.distinctUntilChanged().collect { current = it }
        }
    }

    override fun select(uri: URI?): List<Proxy> = listOf(current ?: Proxy.NO_PROXY)

    override fun connectFailed(
        uri: URI?,
        sa: SocketAddress?,
        ioe: IOException?,
    ) = Unit

    private fun toProxy(
        type: DataStoreManager.ProxyType,
        host: String,
        port: Int,
    ): Proxy? {
        val bareHost = host.substringAfter("://").trim().trimEnd('/')
        if (bareHost.isEmpty() || port !in 1..65535) return null
        return Proxy(
            when (type) {
                DataStoreManager.ProxyType.PROXY_TYPE_HTTP -> Proxy.Type.HTTP
                DataStoreManager.ProxyType.PROXY_TYPE_SOCKS -> Proxy.Type.SOCKS
            },
            InetSocketAddress.createUnresolved(bareHost, port),
        )
    }
}
