package com.localintercom.app.data.network

import com.localintercom.app.domain.model.RoomMember
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
import io.ktor.websocket.send
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class KtorRoomServer(
    private val bindAddress: String,
    private val roomId: String,
    private val roomName: String,
    private val hostName: String,
    private val sessionToken: String,
    hostMember: RoomMember,
    private val onMembersChanged: (List<RoomMember>) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val membersMutex = Mutex()
    private val members = mutableListOf(hostMember)
    private val clients = ConcurrentHashMap<String, ClientSocket>()
    private var server: EmbeddedServer<*, *>? = null
    private var heartbeatJob: Job? = null
    private var closed = false
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
        engine.startSuspend(wait = false)
        port = engine.engine.resolvedConnectors().firstOrNull()?.port
            ?: throw IllegalStateException("The room server did not report its listening port.")
        heartbeatJob = scope.launch {
            while (isActive && !closed) {
                delay(5_000)
                broadcast(RoomProtocol.HEARTBEAT, JSONObject().put("timestamp", System.currentTimeMillis()))
            }
        }
        return port
    }

    private suspend fun handleClient(socket: DefaultWebSocketServerSession) {
        var acceptedMember: RoomMember? = null
        try {
            val firstFrame = withTimeout(10_000) { socket.incoming.receive() }
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

            val member = membersMutex.withLock {
                if (closed || !RoomRules.canAcceptMember(members.size)) {
                    null
                } else {
                    RoomMember(UUID.randomUUID().toString(), displayName, isHost = false).also {
                        members += it
                    }
                }
            }
            if (member == null) {
                reject(socket, if (closed) "room_closed" else "room_full", if (closed) "The host has closed this room." else "This room is full (6/6).")
                return
            }

            acceptedMember = member
            val snapshot = snapshotMembers()
            val roomPayload = RoomProtocol.roomStatePayload(
                roomId, roomName, hostName, snapshot, roomStatus(snapshot)
            )
            socket.send(
                Frame.Text(
                    RoomProtocol.createEvent(
                        RoomProtocol.JOIN_ACCEPTED,
                        JSONObject().put("memberId", member.id).put("room", roomPayload),
                        request.requestId
                    ).toString()
                )
            )
            clients[member.id] = ClientSocket(socket)
            broadcast(RoomProtocol.MEMBER_JOINED, JSONObject().put("member", RoomProtocol.memberToJson(member)))
            broadcastRoomState()
            onMembersChanged(snapshot)
            readClientEvents(socket)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Socket shutdown and malformed client frames are handled as a normal disconnect.
        } finally {
            acceptedMember?.let { removeMember(it.id) }
        }
    }

    private suspend fun readClientEvents(socket: DefaultWebSocketServerSession) {
        for (frame in socket.incoming) {
            if (frame !is Frame.Text) continue
            val event = runCatching { RoomProtocol.parse(frame.readText()) }.getOrNull() ?: continue
            when (event.name) {
                RoomProtocol.LEAVE_ROOM -> return
                RoomProtocol.HEARTBEAT -> Unit
            }
        }
    }

    private suspend fun reject(socket: DefaultWebSocketServerSession, reason: String, message: String) {
        runCatching {
            socket.send(
                Frame.Text(
                    RoomProtocol.createEvent(
                        RoomProtocol.JOIN_REJECTED,
                        JSONObject().put("reason", reason).put("message", message)
                    ).toString()
                )
            )
            socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, message.take(120)))
        }
    }

    private suspend fun removeMember(memberId: String) {
        val removed = membersMutex.withLock {
            clients.remove(memberId)
            members.firstOrNull { it.id == memberId }?.also { members.remove(it) }
        } ?: return
        val snapshot = snapshotMembers()
        broadcast(RoomProtocol.MEMBER_LEFT, JSONObject().put("memberId", removed.id))
        broadcastRoomState()
        onMembersChanged(snapshot)
    }

    private suspend fun snapshotMembers(): List<RoomMember> =
        membersMutex.withLock { members.toList() }

    private fun roomStatus(snapshot: List<RoomMember>): RoomStatus =
        if (snapshot.size >= RoomRules.MAX_MEMBERS) RoomStatus.FULL else RoomStatus.OPEN

    private suspend fun broadcastRoomState() {
        val snapshot = snapshotMembers()
        broadcast(
            RoomProtocol.ROOM_STATE,
            RoomProtocol.roomStatePayload(roomId, roomName, hostName, snapshot, roomStatus(snapshot))
        )
    }

    private suspend fun broadcast(event: String, payload: JSONObject) {
        val message = Frame.Text(RoomProtocol.createEvent(event, payload).toString())
        clients.values.toList().forEach { it.sendSafely(message) }
    }

    suspend fun stop() {
        if (closed) return
        closed = true
        heartbeatJob?.cancel()
        runCatching {
            broadcast(
                RoomProtocol.ROOM_STATE,
                RoomProtocol.roomStatePayload(roomId, roomName, hostName, snapshotMembers(), RoomStatus.CLOSED)
            )
        }
        clients.values.toList().forEach { client ->
            runCatching { client.socket.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Host left the room.")) }
        }
        clients.clear()
        server?.stopSuspend(gracePeriodMillis = 250, timeoutMillis = 1_000)
        server = null
        scope.cancel()
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 8_192L
    }

    private class ClientSocket(val socket: DefaultWebSocketServerSession) {
        private val sendMutex = Mutex()

        suspend fun sendSafely(frame: Frame) {
            runCatching { sendMutex.withLock { socket.send(frame) } }
        }
    }
}
