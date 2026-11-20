package com.localintercom.app.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.localintercom.app.domain.model.DiscoveredRoom
import com.localintercom.app.domain.model.RoomSession
import com.localintercom.app.domain.model.RoomRules
import com.localintercom.app.domain.model.RoomStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AndroidNsdDataSource(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val registrationMutex = Mutex()
    private var registrationListener: NsdManager.RegistrationListener? = null

    suspend fun publish(room: RoomSession) {
        registrationMutex.withLock {
            unpublishLocked()
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "LocalIntercom-${room.roomId.take(8)}"
                serviceType = SERVICE_TYPE
                port = room.port
                setAttribute("id", room.roomId)
                setAttribute("name", room.roomName)
                setAttribute("host", room.hostName)
                setAttribute("count", room.members.size.toString())
                setAttribute("max", "6")
                setAttribute("status", if (room.members.size >= 6) "full" else "open")
            }
            suspendCancellableCoroutine { continuation ->
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {
                        registrationListener = this
                        if (continuation.isActive) continuation.resume(Unit)
                        else runCatching { nsdManager.unregisterService(this) }
                    }

                    override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                NsdException("Room advertisement failed (NSD error $errorCode). Check the Wi-Fi network and try again.")
                            )
                        }
                    }

                    override fun onServiceUnregistered(info: NsdServiceInfo) {
                        if (registrationListener === this) registrationListener = null
                    }

                    override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                        if (registrationListener === this) registrationListener = null
                    }
                }
                continuation.invokeOnCancellation {
                    if (registrationListener === listener) unpublishLocked()
                }
                try {
                    nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (error: Throwable) {
                    continuation.resumeWithException(
                        NsdException("Room advertisement could not start: ${error.message ?: "unknown error"}")
                    )
                }
            }
        }
    }

    suspend fun unpublish() = registrationMutex.withLock { unpublishLocked() }

    private fun unpublishLocked() {
        val listener = registrationListener ?: return
        registrationListener = null
        runCatching { nsdManager.unregisterService(listener) }
    }

    fun discover(): Flow<List<DiscoveredRoom>> = callbackFlow {
        val output = channel
        if (!wifiManager.isWifiEnabled) {
            close(NsdException("Wi-Fi is off. Connect to the same Wi-Fi network as the host, then refresh."))
            return@callbackFlow
        }

        val discovered = linkedMapOf<String, DiscoveredRoom>()
        val resolving = mutableSetOf<String>()
        val multicastLock = wifiManager.createMulticastLock("local-intercom-nsd").apply {
            setReferenceCounted(false)
        }
        try {
            multicastLock.acquire()
        } catch (error: SecurityException) {
            close(NsdException("LAN discovery cannot access Wi-Fi multicast: " + (error.message ?: "permission denied") + "."))
            return@callbackFlow
        }

        lateinit var listener: NsdManager.DiscoveryListener
        val resolveListenerFactory: (String) -> NsdManager.ResolveListener = { serviceName ->
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.remove(serviceName)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolving.remove(serviceName)
                    val room = serviceInfo.toDiscoveredRoom() ?: return
                    discovered[serviceInfo.serviceName] = room
                    output.trySend(discovered.values.toList())
                }
            }
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            @Suppress("DEPRECATION")
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)) return
                if (!resolving.add(serviceInfo.serviceName)) return
                try {
                    nsdManager.resolveService(serviceInfo, resolveListenerFactory(serviceInfo.serviceName))
                } catch (_: RuntimeException) {
                    resolving.remove(serviceInfo.serviceName)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                resolving.remove(serviceInfo.serviceName)
                if (discovered.remove(serviceInfo.serviceName) != null) {
                    output.trySend(discovered.values.toList())
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                output.close(NsdException("LAN discovery failed (NSD error $errorCode). Check Wi-Fi and refresh."))
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
        }
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: RuntimeException) {
            output.close(NsdException("LAN discovery could not start: ${error.message ?: "unknown error"}"))
            multicastLock.release()
            return@callbackFlow
        }

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            if (multicastLock.isHeld) runCatching { multicastLock.release() }
        }
    }

    private fun NsdServiceInfo.toDiscoveredRoom(): DiscoveredRoom? {
        val metadata = attributes
        fun value(key: String): String? = metadata[key]?.let { String(it, StandardCharsets.UTF_8) }
        val roomId = value("id") ?: return null
        val roomName = value("name") ?: return null
        val hostName = value("host") ?: return null
        val address = host?.hostAddress ?: return null
        val count = value("count")?.toIntOrNull() ?: return null
        val max = value("max")?.toIntOrNull() ?: RoomRules.MAX_MEMBERS
        if (!RoomRules.isValidIpAddress(address) || !RoomRules.isValidPort(port)) return null
        if (max !in 1..RoomRules.MAX_MEMBERS || count !in 1..max) return null
        val status = when (value("status")) {
            "full" -> RoomStatus.FULL
            "closed" -> RoomStatus.CLOSED
            else -> RoomStatus.OPEN
        }.let { if (count >= max) RoomStatus.FULL else it }
        return DiscoveredRoom(roomId, roomName, hostName, count, max, status, address, port)
    }

    class NsdException(message: String) : IllegalStateException(message)

    companion object {
        const val SERVICE_TYPE = "_localintercom._tcp."
    }
}
