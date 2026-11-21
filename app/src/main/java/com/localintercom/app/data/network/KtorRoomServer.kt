package com.localintercom.app.data.network

import com.localintercom.app.domain.model.IntercomChannels
import com.localintercom.app.domain.model.RoomMember
import com.localintercom.app.domain.model.RoomRoster
import com.localintercom.app.domain.model.RoomRules
import com.localintercom.app.domain.model.RoomStatus
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitAll
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class KtorRoomServer(
    private val bindAddress: String,
    private val roomId: String,
    private val roomName: String,
    private val hostName: String,
    private val sessionToken: String,
    hostMember: RoomMember,
    private val onMembersChanged: (List<RoomMember>) -> Unit,
    initialMembers: List<RoomMember> = listOf(hostMember)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val roomEventsMutex = Mutex()
    private val roster = RoomRoster(hostMember, initialMembers)
    private val hostMemberId = hostMember.id
    private val clients = ConcurrentHashMap<String, ClientSocket>()
    private var server: EmbeddedServer<*, *>? = null
    private var heartbeatJob: Job? = null
    @Volatile private var closed = false

    var port: Int = 0
        private set

    suspend fun start(): Int {
        check(server == null) { "Room server has already been started." }
        val engine = embeddedServer(CIO, host = bindAddress, port = 0) {
            install(WebSockets) { maxFrameSize = MAX_MESSAGE_BYTES }
            routing {
                webSocket(RoomProtocol.SERVICE_PATH) {
                    handleClient(this)
                }
            }
        }
        server = engine
        try {
            engine.startSuspend(wait = false)
            port = engine.engine.resolvedConnectors().firstOrNull()?.port
                ?: throw IllegalStateException("The room server did not report its listening port.")
        } catch (error: Throwable) {
            try {
                engine.stopSuspend(
                    gracePeriodMillis = SERVER_SHUTDOWN_GRACE_MILLIS,
                    timeoutMillis = SERVER_SHUTDOWN_TIMEOUT_MILLIS
                )
            } finally {
                server = null
                scope.cancel()
            }
            throw error
        }
        heartbeatJob = scope.launch {
            while (isActive && !closed) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                roomEventsMutex.withLock {
                    if (!closed) {
                        broadcast(
                            RoomProtocol.HEARTBEAT,
                            JSONObject().put("timestamp", System.currentTimeMillis())
                        )
                    }
                }
            }
        }
        return port
    }

    private suspend fun handleClient(socket: DefaultWebSocketServerSession) {
        var rosterMemberId: String? = null
        var accepted = false
        try {
            val firstFrame = withTimeout(JOIN_TIMEOUT_MILLIS) { socket.incoming.receive() }
            if (firstFrame !is Frame.Text) {
                reject(socket, "invalid_message", "The first room message must be a join request.")
                return
            }
            val request = runCatching { RoomProtocol.parse(firstFrame.readText()) }.getOrElse {
                reject(socket, "invalid_protocol", it.message ?: "Invalid room protocol.")
                return
            }
            if (request.name != RoomProtocol.JOIN_REQUEST) {
                reject(socket, "join_required", "Send join_request before other room events.")
                return
            }
            val requestedRoomId = request.payload.optString("roomId")
            if (requestedRoomId.isNotBlank() && requestedRoomId != roomId) {
                reject(socket, "room_not_found", "This room ID does not match the selected room.")
                return
            }
            val suppliedToken = request.payload.optString("sessionToken")
            if (!RoomRules.isSessionTokenValid(sessionToken, suppliedToken)) {
                reject(socket, "invalid_token", "The room session token is invalid.")
                return
            }
            val displayName = RoomRules.normalizeDisplayName(request.payload.optString("displayName"))
            if (displayName == null) {
                reject(socket, "invalid_name", "Display name must be 2–24 characters.")
                return
            }

            val requestedMemberId = request.payload.optString("memberId")
            val joinResult = roomEventsMutex.withLock {
                if (closed) {
                    JoinResult.Rejected("room_closed", "The host has closed this room.")
                } else if (requestedMemberId == hostMemberId) {
                    if (clients.containsKey(hostMemberId)) {
                        JoinResult.Rejected("host_already_connected", "The host signaling connection is already active.")
                    } else {
                        val snapshot = roster.snapshot()
                        val socketClient = ClientSocket(socket)
                        val acceptedPayload = JSONObject()
                            .put("memberId", hostMemberId)
                            .put("room", roomStatePayload(snapshot, roomStatus(snapshot)))
                        val acceptedFrame = RoomProtocol.createEvent(
                            RoomProtocol.JOIN_ACCEPTED,
                            acceptedPayload,
                            request.requestId
                        ).toString()
                        if (!socketClient.sendSafely(acceptedFrame)) {
                            throw IOException("Could not deliver the host signaling response.")
                        }
                        rosterMemberId = hostMemberId
                        accepted = true
                        clients[hostMemberId] = socketClient
                        JoinResult.Accepted
                    }
                } else if (requestedMemberId.isNotBlank()) {
                    val snapshot = roster.snapshot()
                    val existingMember = snapshot.firstOrNull {
                        it.id == requestedMemberId && !it.isHost
                    }
                    when {
                        existingMember == null ->
                            JoinResult.Rejected("unknown_member", "This participant is not part of the restored room.")
                        existingMember.displayName != displayName ->
                            JoinResult.Rejected("member_identity_mismatch", "The participant name does not match the restored room.")
                        clients.containsKey(existingMember.id) ->
                            JoinResult.Rejected("member_already_connected", "This participant is already connected.")
                        else -> {
                            val socketClient = ClientSocket(socket)
                            val acceptedPayload = JSONObject()
                                .put("memberId", existingMember.id)
                                .put("room", roomStatePayload(snapshot, roomStatus(snapshot)))
                            val acceptedFrame = RoomProtocol.createEvent(
                                RoomProtocol.JOIN_ACCEPTED,
                                acceptedPayload,
                                request.requestId
                            ).toString()
                            if (!socketClient.sendSafely(acceptedFrame)) {
                                throw IOException("Could not deliver the restored participant response.")
                            }
                            rosterMemberId = existingMember.id
                            accepted = true
                            clients[existingMember.id] = socketClient
                            JoinResult.Accepted
                        }
                    }
                } else {
                    val member = RoomMember(UUID.randomUUID().toString(), displayName, isHost = false)
                    val change = roster.tryAdd(member)
                    if (change == null) {
                        JoinResult.Rejected("room_full", "This room is full (6/6).")
                    } else {
                        rosterMemberId = member.id
                        val socketClient = ClientSocket(socket)
                        val acceptedPayload = JSONObject()
                            .put("memberId", member.id)
                            .put("room", roomStatePayload(change.members, roomStatus(change.members)))
                        val acceptedFrame = RoomProtocol.createEvent(
                            RoomProtocol.JOIN_ACCEPTED,
                            acceptedPayload,
                            request.requestId
                        ).toString()
                        if (!socketClient.sendSafely(acceptedFrame)) {
                            throw IOException("Could not deliver the room join response.")
                        }

                        accepted = true
                        clients[member.id] = socketClient
                        broadcast(
                            RoomProtocol.MEMBER_JOINED,
                            JSONObject().put("member", RoomProtocol.memberToJson(member))
                        )
                        broadcastRoomState(change.members)
                        onMembersChanged(change.members)
                        JoinResult.Accepted
                    }
                }
            }

            when (joinResult) {
                is JoinResult.Rejected -> reject(socket, joinResult.reason, joinResult.message)
                JoinResult.Accepted -> readClientEvents(socket, rosterMemberId ?: return)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Socket shutdown and malformed client frames are handled as a normal disconnect.
        } finally {
            rosterMemberId?.let { removeMember(it, announce = accepted) }
        }
    }

    private suspend fun readClientEvents(socket: DefaultWebSocketServerSession, memberId: String) {
        for (frame in socket.incoming) {
            if (frame !is Frame.Text) continue
            val event = runCatching { RoomProtocol.parse(frame.readText()) }.getOrNull() ?: continue
            when (event.name) {
                RoomProtocol.LEAVE_ROOM -> return
                RoomProtocol.HEARTBEAT -> Unit
                RoomProtocol.CHANNEL_CHANGED -> {
                    val channelId = event.payload.optString("channelId")
                    if (!IntercomChannels.contains(channelId)) continue
                    val change = roster.updateChannel(memberId, channelId) ?: continue
                    broadcastRoomState(change.members)
                    onMembersChanged(change.members)
                }
                RoomProtocol.SIGNALING -> routeSignaling(memberId, event.payload)
            }
        }
    }

    private suspend fun reject(socket: DefaultWebSocketServerSession, reason: String, message: String) {
        val client = ClientSocket(socket)
        val response = RoomProtocol.createEvent(
            RoomProtocol.JOIN_REJECTED,
            JSONObject().put("reason", reason).put("message", message)
        ).toString()
        client.sendSafely(response)
        client.closeSafely(CloseReason(CloseReason.Codes.VIOLATED_POLICY, message.take(120)))
    }

    private suspend fun removeMember(memberId: String, announce: Boolean) {
        roomEventsMutex.withLock {
            clients.remove(memberId)
            val change = roster.remove(memberId) ?: return
            if (closed || !announce) return

            broadcast(
                RoomProtocol.MEMBER_LEFT,
                JSONObject().put("memberId", change.member.id)
            )
            broadcastRoomState(change.members)
            onMembersChanged(change.members)
        }
    }

    private suspend fun routeSignaling(senderId: String, payload: JSONObject) {
        val targetId = payload.optString("toMemberId")
        val signalType = payload.optString("signalType")
        val data = payload.optJSONObject("data") ?: return
        if (targetId.isBlank() || targetId == senderId) return
        if (signalType !in setOf(RoomProtocol.SIGNAL_OFFER, RoomProtocol.SIGNAL_ANSWER, RoomProtocol.SIGNAL_ICE)) return
        val target = clients[targetId] ?: return
        val forwarded = JSONObject()
            .put("fromMemberId", senderId)
            .put("signalType", signalType)
            .put("data", data)
        target.sendSafely(RoomProtocol.createEvent(RoomProtocol.SIGNALING, forwarded).toString())
    }

    private fun roomStatus(snapshot: List<RoomMember>): RoomStatus =
        if (snapshot.size >= RoomRules.MAX_MEMBERS) RoomStatus.FULL else RoomStatus.OPEN

    private fun roomStatePayload(
        members: List<RoomMember>,
        status: RoomStatus
    ): JSONObject = RoomProtocol.roomStatePayload(roomId, roomName, hostName, members, status)

    private suspend fun broadcastRoomState(members: List<RoomMember>) {
        broadcast(RoomProtocol.ROOM_STATE, roomStatePayload(members, roomStatus(members)))
    }

    private suspend fun broadcast(event: String, payload: JSONObject) {
        val message = RoomProtocol.createEvent(event, payload).toString()
        val recipients = clients.values.toList()
        coroutineScope {
            recipients.map { client -> async { client.sendSafely(message) } }.awaitAll()
        }
    }

    suspend fun stop() {
        val shouldStop = roomEventsMutex.withLock {
            if (closed) {
                false
            } else {
                closed = true
                heartbeatJob?.cancel()
                val members = roster.snapshot()
                runCatching {
                    broadcast(
                        RoomProtocol.ROOM_STATE,
                        roomStatePayload(members, RoomStatus.CLOSED)
                    )
                }
                val activeClients = clients.values.toList()
                clients.clear()
                activeClients.forEach { client ->
                    client.closeSafely(
                        CloseReason(CloseReason.Codes.GOING_AWAY, "Host left the room.")
                    )
                }
                true
            }
        }
        if (!shouldStop) return

        try {
            server?.stopSuspend(
                gracePeriodMillis = SERVER_SHUTDOWN_GRACE_MILLIS,
                timeoutMillis = SERVER_SHUTDOWN_TIMEOUT_MILLIS
            )
        } finally {
            server = null
            scope.cancel()
        }
    }

    private sealed interface JoinResult {
        data object Accepted : JoinResult
        data class Rejected(val reason: String, val message: String) : JoinResult
    }

    private class ClientSocket(val socket: DefaultWebSocketServerSession) {
        private val sendMutex = Mutex()

        suspend fun sendSafely(message: String): Boolean {
            return try {
                withTimeout(SOCKET_SEND_TIMEOUT_MILLIS) {
                    sendMutex.withLock { socket.send(Frame.Text(message)) }
                }
                true
            } catch (_: TimeoutCancellationException) {
                closeSafely(CloseReason(CloseReason.Codes.GOING_AWAY, "WebSocket send timed out."))
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                closeSafely(CloseReason(CloseReason.Codes.GOING_AWAY, "WebSocket send failed."))
                false
            }
        }

        suspend fun closeSafely(reason: CloseReason) {
            runCatching {
                withTimeout(SOCKET_CLOSE_TIMEOUT_MILLIS) {
                    sendMutex.withLock { socket.close(reason) }
                }
            }
        }
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 8_192L
        private const val JOIN_TIMEOUT_MILLIS = 10_000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 5_000L
        private const val SOCKET_SEND_TIMEOUT_MILLIS = 2_000L
        private const val SOCKET_CLOSE_TIMEOUT_MILLIS = 500L
        private const val SERVER_SHUTDOWN_GRACE_MILLIS = 250L
        private const val SERVER_SHUTDOWN_TIMEOUT_MILLIS = 1_000L
    }
}
