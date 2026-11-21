package com.localintercom.app.data.network

import com.localintercom.app.domain.model.IntercomChannels
import com.localintercom.app.domain.model.RoomConnectionStatus
import com.localintercom.app.domain.model.RoomSession
import com.localintercom.app.domain.model.RoomStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import org.json.JSONObject

internal class KtorRoomClient(
    private val scope: CoroutineScope,
    private val onSessionUpdated: (RoomSession) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onSignal: (fromMemberId: String, signalType: String, data: JSONObject) -> Unit = { _, _, _ -> },
    private val onMemberRejoined: (memberId: String) -> Unit = { }
) {
    private val httpClient = HttpClient(CIO) {
        install(WebSockets) { maxFrameSize = MAX_MESSAGE_BYTES }
    }
    @Volatile private var session: DefaultClientWebSocketSession? = null
    @Volatile private var receiveJob: Job? = null
    private val sendMutex = Mutex()

    suspend fun join(
        hostAddress: String,
        port: Int,
        roomId: String,
        displayName: String,
        sessionToken: String,
        localMemberId: String? = null,
        isHost: Boolean = false,
        hostControlToken: String? = null
    ): RoomSession {
        val normalizedAddress = hostAddress.removePrefix("[").removeSuffix("]")
        val urlHost = if (normalizedAddress.contains(':')) "[$normalizedAddress]" else normalizedAddress
        val socket = withTimeout(CONNECTION_TIMEOUT_MILLIS) {
            httpClient.webSocketSession("ws://$urlHost:$port${RoomProtocol.SERVICE_PATH}")
        }
        try {
            sendFrame(
                socket,
                Frame.Text(RoomProtocol.joinRequest(roomId, displayName, sessionToken, localMemberId, hostControlToken).toString())
            )
            val firstFrame = withTimeout(CONNECTION_TIMEOUT_MILLIS) { socket.incoming.receive() }
            if (firstFrame !is Frame.Text) throw IllegalStateException("The room returned an invalid response.")
            val event = RoomProtocol.parse(firstFrame.readText())
            when (event.name) {
                RoomProtocol.JOIN_REJECTED -> {
                    val reason = event.payload.optString("message", "The host rejected the join request.")
                    throw RoomJoinRejectedException(reason)
                }
                RoomProtocol.JOIN_ACCEPTED -> Unit
                else -> throw IllegalStateException("The room returned an unexpected response.")
            }
            val room = event.payload.optJSONObject("room")
                ?: throw IllegalStateException("The host returned an incomplete room state.")
            val members = RoomProtocol.parseMembers(room.optJSONArray("members") ?: org.json.JSONArray())
            val actualRoomId = room.optString("roomId").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("The host returned an invalid room ID.")
            val acceptedMemberId = event.payload.optString("memberId").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("The host did not return a participant ID.")
            val joinedRoom = RoomSession(
                roomId = actualRoomId,
                roomName = room.optString("roomName", "Intercom room"),
                hostName = room.optString("hostName", "Host"),
                members = members,
                isHost = isHost,
                connectionStatus = RoomConnectionStatus.CONNECTED,
                hostAddress = normalizedAddress,
                port = port,
                joinToken = sessionToken,
                localMemberId = acceptedMemberId
            )
            session = socket
            onSessionUpdated(joinedRoom)
            receiveJob = scope.launch(Dispatchers.IO) { receiveEvents(socket, joinedRoom) }
            return joinedRoom
        } catch (error: Throwable) {
            runCatching { socket.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Join failed.")) }
            throw error
        }
    }

    private suspend fun receiveEvents(
        socket: DefaultClientWebSocketSession,
        initialRoom: RoomSession
    ) {
        var room = initialRoom
        var roomClosedByHost = false
        try {
            while (true) {
                val frame = withTimeoutOrNull(HEARTBEAT_TIMEOUT_MILLIS) {
                    socket.incoming.receive()
                } ?: throw IOException("The room host heartbeat timed out.")
                if (frame !is Frame.Text) continue
                val event = runCatching { RoomProtocol.parse(frame.readText()) }.getOrNull() ?: continue
                when (event.name) {
                    RoomProtocol.ROOM_STATE -> {
                        val members = RoomProtocol.parseMembers(
                            event.payload.optJSONArray("members") ?: org.json.JSONArray()
                        )
                        val isClosed = event.payload.optString("status") == RoomStatus.CLOSED.name.lowercase()
                        room = room.copy(
                            roomName = event.payload.optString("roomName", room.roomName),
                            hostName = event.payload.optString("hostName", room.hostName),
                            members = members,
                            connectionStatus = if (isClosed) RoomConnectionStatus.DISCONNECTED else RoomConnectionStatus.CONNECTED
                        )
                        onSessionUpdated(room)
                        if (isClosed) {
                            roomClosedByHost = true
                            break
                        }
                    }
                    RoomProtocol.MEMBER_REJOINED -> {
                        val memberId = event.payload.optString("memberId")
                        if (memberId.isNotBlank()) onMemberRejoined(memberId)
                    }
                    RoomProtocol.SIGNALING -> {
                        val fromMemberId = event.payload.optString("fromMemberId")
                        val signalType = event.payload.optString("signalType")
                        val data = event.payload.optJSONObject("data") ?: continue
                        if (fromMemberId.isNotBlank()) onSignal(fromMemberId, signalType, data)
                    }
                    RoomProtocol.HEARTBEAT -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A closed socket is reflected in the connection state below.
        } finally {
            if (session === socket) {
                session = null
                room = room.copy(connectionStatus = RoomConnectionStatus.DISCONNECTED)
                onSessionUpdated(room)
                if (!roomClosedByHost) onDisconnected()
            }
        }
    }

    suspend fun sendSignal(toMemberId: String, signalType: String, data: JSONObject) {
        require(toMemberId.isNotBlank()) { "A signaling recipient is required." }
        require(signalType in setOf(RoomProtocol.SIGNAL_OFFER, RoomProtocol.SIGNAL_ANSWER, RoomProtocol.SIGNAL_ICE)) {
            "Unsupported signaling message."
        }
        val socket = session ?: throw IllegalStateException("The room signaling connection is not active.")
        val payload = JSONObject()
            .put("toMemberId", toMemberId)
            .put("signalType", signalType)
            .put("data", data)
        sendFrame(socket, Frame.Text(RoomProtocol.createEvent(RoomProtocol.SIGNALING, payload).toString()))
    }

    suspend fun selectChannel(channelId: String) {
        require(IntercomChannels.contains(channelId)) { "Unknown intercom channel." }
        val socket = session ?: throw IllegalStateException("The room signaling connection is not active.")
        sendFrame(
            socket,
            Frame.Text(
                RoomProtocol.createEvent(
                    RoomProtocol.CHANNEL_CHANGED,
                    JSONObject().put("channelId", channelId)
                ).toString()
            )
        )
    }

    private suspend fun sendFrame(socket: DefaultClientWebSocketSession, frame: Frame) {
        sendMutex.withLock { socket.send(frame) }
    }

    suspend fun leave() {
        val socket = session
        session = null
        if (socket != null) {
            runCatching {
                sendMutex.withLock {
                    socket.send(Frame.Text(RoomProtocol.createEvent(RoomProtocol.LEAVE_ROOM).toString()))
                    socket.close(CloseReason(CloseReason.Codes.NORMAL, "Leaving room."))
                }
            }
        }
        receiveJob?.cancel()
        receiveJob = null
        httpClient.close()
    }

    class RoomJoinRejectedException(message: String) : IllegalStateException(message)

    companion object {
        private const val CONNECTION_TIMEOUT_MILLIS = 10_000L
        private const val MAX_MESSAGE_BYTES = 8_192L
        private const val HEARTBEAT_TIMEOUT_MILLIS = 6_500L
    }
}
