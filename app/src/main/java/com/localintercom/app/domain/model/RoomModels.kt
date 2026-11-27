package com.localintercom.app.domain.model

enum class RoomStatus {
    OPEN,
    FULL,
    CLOSED
}

enum class RoomConnectionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

data class RoomMember(
    val id: String,
    val displayName: String,
    val isHost: Boolean,
    val channelId: String = IntercomChannels.GENERAL
)

data class RoomSession(
    val roomId: String,
    val roomName: String,
    val hostName: String,
    val members: List<RoomMember>,
    val isHost: Boolean,
    val connectionStatus: RoomConnectionStatus,
    val hostAddress: String,
    val port: Int,
    /** Present on the host and in-memory participant session only; never broadcast in room state. */
    val joinToken: String? = null,
    val localMemberId: String = "",
    val selectedChannelId: String = IntercomChannels.GENERAL
) {
    val status: RoomStatus
        get() = when {
            members.size >= RoomRules.MAX_MEMBERS -> RoomStatus.FULL
            else -> RoomStatus.OPEN
        }
}

data class DiscoveredRoom(
    val roomId: String,
    val roomName: String,
    val hostName: String,
    val memberCount: Int,
    val maxMembers: Int,
    val status: RoomStatus,
    val hostAddress: String,
    val port: Int
)

data class DiscoveryState(
    val isScanning: Boolean = false,
    val message: String = "Room discovery is idle."
)
