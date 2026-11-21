package com.localintercom.app.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.localintercom.app.data.audio.WebRtcAudioMesh
import com.localintercom.app.data.discovery.AndroidNsdDataSource
import com.localintercom.app.data.network.KtorRoomClient
import com.localintercom.app.data.network.KtorRoomServer
import com.localintercom.app.service.IntercomAudioService
import com.localintercom.app.domain.model.DiscoveredRoom
import com.localintercom.app.domain.model.DiscoveryState
import com.localintercom.app.domain.model.IntercomChannels
import com.localintercom.app.domain.model.RoomConnectionStatus
import com.localintercom.app.domain.model.RoomMember
import com.localintercom.app.domain.model.RoomRules
import com.localintercom.app.domain.model.RoomSession
import com.localintercom.app.domain.repository.RoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.UUID

class KtorRoomRepository(context: Context) : RoomRepository {
    private val appContext = context.applicationContext
    private val nsd = AndroidNsdDataSource(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val roomMutex = Mutex()
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    private val _activeRoom = MutableStateFlow<RoomSession?>(null)
    override val activeRoom: StateFlow<RoomSession?> = _activeRoom.asStateFlow()

    private val _discoveredRooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    override val discoveredRooms: StateFlow<List<DiscoveredRoom>> = _discoveredRooms.asStateFlow()

    private val _discoveryState = MutableStateFlow(DiscoveryState())
    override val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private var discoveryJob: kotlinx.coroutines.Job? = null
    private var server: KtorRoomServer? = null
    private var client: KtorRoomClient? = null
    private var audioMesh: WebRtcAudioMesh? = null
    @Volatile private var isLeavingHostRoom = false

    init {
        IntercomAudioService.pttCallback = { active -> audioMesh?.setPttActive(active) }
    }

    override suspend fun createRoom(roomName: String, displayName: String) {
        val normalizedRoomName = RoomRules.normalizeRoomName(roomName)
            ?: throw IllegalArgumentException("Room name must be 2–32 characters.")
        val normalizedDisplayName = RoomRules.normalizeDisplayName(displayName)
            ?: throw IllegalArgumentException("Display name must be 2–24 characters.")

        roomMutex.withLock {
            check(_activeRoom.value == null) { "Leave the current room before creating another one." }
            val hostAddress = findLocalAddress()
                ?: throw IllegalStateException("Connect to the same Wi-Fi or local network as the other participants first.")
            val roomId = UUID.randomUUID().toString()
            val joinToken = createSessionToken()
            val hostMember = RoomMember(UUID.randomUUID().toString(), normalizedDisplayName, isHost = true)
            val roomServer = KtorRoomServer(
                bindAddress = hostAddress,
                roomId = roomId,
                roomName = normalizedRoomName,
                hostName = normalizedDisplayName,
                sessionToken = joinToken,
                hostMember = hostMember,
                onMembersChanged = ::onHostMembersChanged
            )
            var hostClient: KtorRoomClient? = null
            try {
                val port = withContext(Dispatchers.IO) { roomServer.start() }
                server = roomServer
                _activeRoom.value = RoomSession(
                    roomId = roomId,
                    roomName = normalizedRoomName,
                    hostName = normalizedDisplayName,
                    members = listOf(hostMember),
                    isHost = true,
                    connectionStatus = RoomConnectionStatus.CONNECTED,
                    hostAddress = hostAddress,
                    port = port,
                    joinToken = joinToken,
                    localMemberId = hostMember.id
                )

                hostClient = createRoomClient()
                val hostSession = withContext(Dispatchers.IO) {
                    hostClient.join(
                        hostAddress = hostAddress,
                        port = port,
                        roomId = roomId,
                        displayName = normalizedDisplayName,
                        sessionToken = joinToken,
                        localMemberId = hostMember.id,
                        isHost = true
                    )
                }
                client = hostClient
                val session = hostSession.copy(joinToken = joinToken)
                _activeRoom.value = session
                startAudioMesh(session, hostClient)

                try {
                    nsd.publish(session)
                    _discoveryState.value = DiscoveryState(message = "Room is advertised on the local network.")
                } catch (error: Throwable) {
                    _discoveryState.value = DiscoveryState(
                        message = "Room is running, but LAN advertisement failed: ${error.message ?: "unknown error"}. Share the host IP, port, and session token for manual join."
                    )
                }
            } catch (cancelled: CancellationException) {
                runCatching { hostClient?.leave() }
                runCatching { audioMesh?.close() }
                audioMesh = null
                runCatching { roomServer.stop() }
                server = null
                client = null
                _activeRoom.value = null
                throw cancelled
            } catch (error: Throwable) {
                runCatching { hostClient?.leave() }
                runCatching { audioMesh?.close() }
                audioMesh = null
                runCatching { roomServer.stop() }
                server = null
                client = null
                _activeRoom.value = null
                throw IllegalStateException("Could not start the room: ${error.message ?: "unknown error"}", error)
            }
        }
    }

    override fun refreshDiscovery() {
        discoveryJob?.cancel()
        _discoveredRooms.value = emptyList()
        _discoveryState.value = DiscoveryState(isScanning = true, message = "Searching this Wi-Fi network for rooms…")
        discoveryJob = scope.launch {
            try {
                withTimeout(DISCOVERY_WINDOW_MILLIS) {
                    nsd.discover().collect { rooms ->
                        _discoveredRooms.value = rooms
                        _discoveryState.value = DiscoveryState(
                            isScanning = true,
                            message = if (rooms.isEmpty()) {
                                "Searching this Wi-Fi network for rooms…"
                            } else {
                                "Found ${rooms.size} room${if (rooms.size == 1) "" else "s"}."
                            }
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // The scan window ended normally; keep any rooms already found.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _discoveredRooms.value = emptyList()
                _discoveryState.value = DiscoveryState(
                    isScanning = false,
                    message = error.message ?: "LAN discovery failed. Check the Wi-Fi network and refresh."
                )
                return@launch
            }
            val foundRooms = _discoveredRooms.value
            _discoveryState.value = DiscoveryState(
                isScanning = false,
                message = if (foundRooms.isEmpty()) {
                    "No rooms found. Confirm both devices use the same Wi-Fi and multicast is allowed. You can also join by IP and port."
                } else {
                    "Discovery complete. Found ${foundRooms.size} room${if (foundRooms.size == 1) "" else "s"}."
                }
            )
        }
    }

    override suspend fun joinDiscoveredRoom(room: DiscoveredRoom, displayName: String, sessionToken: String) {
        join(room.hostAddress, room.port, room.roomId, displayName, sessionToken)
    }

    override suspend fun joinManualRoom(hostAddress: String, port: Int, displayName: String, sessionToken: String) {
        join(hostAddress, port, roomId = "", displayName, sessionToken)
    }

    private suspend fun join(
        hostAddress: String,
        port: Int,
        roomId: String,
        displayName: String,
        sessionToken: String
    ) {
        roomMutex.withLock {
            check(_activeRoom.value == null) { "Leave the current room before joining another one." }
            val roomClient = createRoomClient()
            try {
                val joinedRoom = withContext(Dispatchers.IO) {
                    roomClient.join(hostAddress, port, roomId, displayName, sessionToken)
                }
                client = roomClient
                _activeRoom.value = joinedRoom
                startAudioMesh(joinedRoom, roomClient)
            } catch (cancelled: CancellationException) {
                runCatching { roomClient.leave() }
                throw cancelled
            } catch (error: Throwable) {
                runCatching { roomClient.leave() }
                throw error
            }
        }
    }

    private fun createRoomClient(): KtorRoomClient = KtorRoomClient(
        scope = scope,
        onSessionUpdated = { updated ->
            _activeRoom.update { current ->
                updated.copy(
                    joinToken = current?.joinToken ?: updated.joinToken,
                    selectedChannelId = current?.selectedChannelId ?: updated.selectedChannelId
                )
            }
            audioMesh?.syncMembers(updated.members, updated.selectedChannelId)
        },
        onDisconnected = {
            _activeRoom.update { it?.copy(connectionStatus = RoomConnectionStatus.DISCONNECTED) }
        },
        onSignal = { fromMemberId, signalType, data ->
            audioMesh?.handleSignal(fromMemberId, signalType, data)
        }
    )

    private fun startAudioMesh(room: RoomSession, roomClient: KtorRoomClient) {
        audioMesh?.close()
        audioMesh = WebRtcAudioMesh(
            context = appContext,
            scope = scope,
            localMemberId = room.localMemberId,
            sendSignal = { toMemberId, signalType, data ->
                scope.launch {
                    runCatching { roomClient.sendSignal(toMemberId, signalType, data) }
                }
            },
            onStatusChanged = { /* surfaced in the room UI in a later presentation update */ }
        ).also {
            it.syncMembers(room.members, room.selectedChannelId)
            IntercomAudioService.start(appContext)
        }
    }

    override suspend fun selectChannel(channelId: String) {
        require(IntercomChannels.contains(channelId)) { "Unknown intercom channel." }
        val current = _activeRoom.value ?: throw IllegalStateException("Join a room before changing channels.")
        _activeRoom.value = current.copy(selectedChannelId = channelId)
        audioMesh?.syncMembers(current.members, channelId)
        client?.selectChannel(channelId)
    }

    override fun setPttActive(active: Boolean) {
        audioMesh?.setPttActive(active)
    }

    override suspend fun leaveRoom() {
        roomMutex.withLock {
            val current = _activeRoom.value
            audioMesh?.close()
            audioMesh = null
            IntercomAudioService.pttCallback = null
            IntercomAudioService.stop(appContext)
            if (current?.isHost == true) {
                isLeavingHostRoom = true
                try {
                    runCatching { nsd.unpublish() }
                    client?.leave()
                    client = null
                    runCatching { server?.stop() }
                    server = null
                } finally {
                    _activeRoom.value = null
                    isLeavingHostRoom = false
                }
            } else {
                client?.leave()
                client = null
                _activeRoom.value = null
            }
            _discoveryState.update {
                it.copy(isScanning = false, message = "Room closed.")
            }
        }
    }

    override fun releaseWhenUiCloses() {
        discoveryJob?.cancel()
        scope.launch { runCatching { leaveRoom() } }
    }

    private fun onHostMembersChanged(members: List<RoomMember>) {
        _activeRoom.update { room ->
            if (room?.isHost == true) room.copy(members = members) else room
        }
        _activeRoom.value?.let { room -> audioMesh?.syncMembers(room.members, room.selectedChannelId) }
        val updatedRoom = _activeRoom.value ?: return
        if (updatedRoom.isHost && !isLeavingHostRoom) {
            scope.launch {
                val latestRoom = _activeRoom.value?.takeIf { it.isHost && it.roomId == updatedRoom.roomId }
                    ?: return@launch
                if (isLeavingHostRoom) return@launch
                runCatching { nsd.publish(latestRoom) }
                    .onFailure { error ->
                        _discoveryState.value = DiscoveryState(
                            message = "Room is active, but its LAN listing could not refresh: ${error.message ?: "unknown error"}."
                        )
                    }
            }
        }
    }

    private fun findLocalAddress(): String? {
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val addresses = connectivityManager.getLinkProperties(network)?.linkAddresses
            ?.mapNotNull { it.address }
            ?: return null
        return addresses.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
            ?: addresses.firstOrNull { it.isSiteLocalAddress || it.isLinkLocalAddress }?.hostAddress
    }

    private fun createSessionToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val DISCOVERY_WINDOW_MILLIS = 12_000L
        private const val TOKEN_BYTES = 32
    }
}
