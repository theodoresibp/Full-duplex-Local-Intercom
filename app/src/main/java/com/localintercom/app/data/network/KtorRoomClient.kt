package com.localintercom.app.data.network

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
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal class KtorRoomClient(
    private val scope: CoroutineScope,
    private val onSessionUpdated: (RoomSession) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val httpClient = HttpClient(CIO) {
        install(WebSockets) { maxFrameSize = MAX_MESSAGE_BYTES }
    }
    private var session: DefaultClientWebSocketSession? = null
    private var receiveJob: Job? = null

    suspend fun join(
        hostAddress: String,
        port: Int,
        roomId: String,
        displayName: String,
        sessionToken: String
    ): RoomSession {
        val normalizedAddress = hostAddress.removePrefix("[").removeSuffix("]")
        val urlHost = if (normalizedAddress.contains(':')) "[$normalizedAddress]" else normalizedAddress
        val socket = withTimeout(CONNECTION_TIMEOUT_MILLIS) {
            httpClient.webSocketSession("ws://$urlHost:$port${RoomProtocol.SERVICE_PATH}")
        }
        try {
            socket.send(Frame.Text(RoomProtocol.joinRequest(roomId, displayName, sessionToken).toString()))
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
            val joinedRoom = RoomSession(
                roomId = actualRoomId,
                roomName = room.optString("roomName", "Intercom room"),
                hostName = room.optString("hostName", "Host"),
                members = members,
                isHost = false,
                connectionStatus = RoomConnectionStatus.CONNECTED,
                hostAddress = normalizedAddress,
                port = port
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
        try {
            for (frame in socket.incoming) {
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
                        if (isClosed) break
                    }
                    RoomProtocol.HEARTBEAT -> {
                        socket.send(
                            Frame.Text(
                                RoomProtocol.createEvent(
                                    RoomProtocol.HEARTBEAT,
                                    JSONObject().put("timestamp", System.currentTimeMillis())
                                ).toString()
                            )
                        )
                    }
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
                onDisconnected()
            }
        }
    }

    suspend fun leave() {
        val socket = session
        session = null
        if (socket != null) {
            runCatching {
                socket.send(Frame.Text(RoomProtocol.createEvent(RoomProtocol.LEAVE_ROOM).toString()))
                socket.close(CloseReason(CloseReason.Codes.NORMAL, "Leaving room."))
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
    }
}
