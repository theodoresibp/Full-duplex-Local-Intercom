package com.localintercom.app.data.network

import com.localintercom.app.domain.model.IntercomChannels
import com.localintercom.app.domain.model.RoomMember
import com.localintercom.app.domain.model.RoomStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object RoomProtocol {
    const val VERSION = 1
    const val SERVICE_PATH = "/room"

    const val JOIN_REQUEST = "join_request"
    const val JOIN_ACCEPTED = "join_accepted"
    const val JOIN_REJECTED = "join_rejected"
    const val MEMBER_JOINED = "member_joined"
    const val MEMBER_LEFT = "member_left"
    const val ROOM_STATE = "room_state"
    const val HEARTBEAT = "heartbeat"
    const val LEAVE_ROOM = "leave_room"
    const val SIGNALING = "signaling"
    const val CHANNEL_CHANGED = "channel_changed"

    const val SIGNAL_OFFER = "offer"
    const val SIGNAL_ANSWER = "answer"
    const val SIGNAL_ICE = "ice_candidate"

    data class Event(val name: String, val requestId: String, val payload: JSONObject)

    fun createEvent(name: String, payload: JSONObject = JSONObject(), requestId: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject()
            .put("version", VERSION)
            .put("event", name)
            .put("requestId", requestId)
            .put("payload", payload)

    fun joinRequest(
        roomId: String,
        displayName: String,
        sessionToken: String,
        memberId: String? = null,
        hostToken: String? = null
    ): JSONObject {
        val payload = JSONObject()
            .put("roomId", roomId)
            .put("displayName", displayName)
            .put("sessionToken", sessionToken)
        if (!memberId.isNullOrBlank()) payload.put("memberId", memberId)
        if (!hostToken.isNullOrBlank()) payload.put("hostToken", hostToken)
        return createEvent(JOIN_REQUEST, payload)
    }

    fun parse(raw: String): Event {
        val json = JSONObject(raw)
        require(json.optInt("version", -1) == VERSION) { "Unsupported room protocol version." }
        val name = json.optString("event").takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Room message is missing its event.")
        val payload = json.optJSONObject("payload")
            ?: throw IllegalArgumentException("Room message is missing its payload.")
        return Event(name, json.optString("requestId"), payload)
    }

    fun memberToJson(member: RoomMember): JSONObject =
        JSONObject()
            .put("id", member.id)
            .put("displayName", member.displayName)
            .put("isHost", member.isHost)
            .put("channelId", member.channelId)

    fun membersToJson(members: List<RoomMember>): JSONArray =
        JSONArray().also { array -> members.forEach { array.put(memberToJson(it)) } }

    fun parseMembers(array: JSONArray): List<RoomMember> =
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { json ->
                val id = json.optString("id").takeIf(String::isNotBlank) ?: return@let null
                val displayName = json.optString("displayName").takeIf(String::isNotBlank) ?: return@let null
                val channelId = json.optString("channelId", IntercomChannels.GENERAL)
                    .takeIf(IntercomChannels::contains) ?: IntercomChannels.GENERAL
                RoomMember(id, displayName, json.optBoolean("isHost"), channelId)
            }
        }

    fun roomStatePayload(
        roomId: String,
        roomName: String,
        hostName: String,
        members: List<RoomMember>,
        status: RoomStatus
    ): JSONObject = JSONObject()
        .put("roomId", roomId)
        .put("roomName", roomName)
        .put("hostName", hostName)
        .put("members", membersToJson(members))
        .put("status", status.name.lowercase())
}
