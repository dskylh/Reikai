package reikai.data.novel.tts

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Finds PC render servers on the local network, which announce themselves over mDNS as
 * [SERVICE_TYPE] per the server contract. Android resolves one service at a time before API 34, so
 * found services wait their turn in a queue.
 */
@Inject
class TtsServerDiscovery(private val context: Context) {

    /** The servers answering right now, updated as they come and go; ends if discovery cannot start. */
    fun servers(): Flow<List<FoundTtsServer>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val lock = Any()
        val found = LinkedHashMap<String, FoundTtsServer>()
        val waiting = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun publish() = trySend(synchronized(lock) { found.values.toList() })

        fun resolveNext() {
            val next = synchronized(lock) {
                if (resolving) return
                waiting.removeFirstOrNull()?.also { resolving = true }
            } ?: return
            @Suppress("DEPRECATION")
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.address()
                        synchronized(lock) {
                            resolving = false
                            if (host !=
                                null
                            ) {
                                found[info.serviceName] = FoundTtsServer(info.serviceName, host, info.port)
                            }
                        }
                        publish()
                        resolveNext()
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }
                        resolveNext()
                    }
                },
            )
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(lock) { waiting.addLast(info) }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(lock) { found.remove(info.serviceName) }
                publish()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        publish()
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    // An IPv4 address where the server has one: it is what a user recognises and types.
    private fun NsdServiceInfo.address(): String? {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(host)
        }
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return null
        val text = address.hostAddress ?: return null
        return if (address is Inet6Address) "[${text.substringBefore('%')}]" else text
    }

    companion object {
        const val SERVICE_TYPE = "_reikai-tts._tcp"
    }
}

/** A render server found on the local network, at [host] (an IP address) and [port]. */
data class FoundTtsServer(val name: String, val host: String, val port: Int) {
    val address: String get() = "http://$host:$port"
}
