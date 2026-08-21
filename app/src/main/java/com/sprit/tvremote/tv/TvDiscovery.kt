package com.sprit.tvremote.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class DiscoveredTv(val name: String, val host: String)

/**
 * Поиск телевизоров в локальной сети через mDNS: сервис пульта Remote v2 объявляет себя как
 * `_androidtvremote2._tcp`.
 */
class TvDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION")
    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        val found = linkedMapOf<String, DiscoveredTv>()
        // Резолвить по одному: параллельные запросы NsdManager отклоняет.
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        launch {
            for (service in queue) {
                val resolved = resolve(service) ?: continue
                val address = resolved.host?.hostAddress ?: continue
                found[resolved.serviceName] = DiscoveredTv(resolved.serviceName, address)
                trySend(found.values.toList())
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                queue.trySend(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                if (found.remove(service.serviceName) != null) {
                    trySend(found.values.toList())
                }
            }

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            queue.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            nsd.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(serviceInfo)
                    }
                },
            )
        }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}
