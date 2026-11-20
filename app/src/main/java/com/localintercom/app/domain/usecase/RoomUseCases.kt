package com.localintercom.app.domain.usecase

import com.localintercom.app.domain.model.RoomRules
import com.localintercom.app.domain.repository.RoomRepository

class CreateRoomUseCase(private val repository: RoomRepository) {
    suspend operator fun invoke(roomName: String, displayName: String) {
        val normalizedRoomName = RoomRules.normalizeRoomName(roomName)
            ?: throw IllegalArgumentException("Room name must be 2–32 characters.")
        val normalizedDisplayName = RoomRules.normalizeDisplayName(displayName)
            ?: throw IllegalArgumentException("Display name must be 2–24 characters.")
        repository.createRoom(normalizedRoomName, normalizedDisplayName)
    }
}

class JoinDiscoveredRoomUseCase(private val repository: RoomRepository) {
    suspend operator fun invoke(host: com.localintercom.app.domain.model.DiscoveredRoom, displayName: String, sessionToken: String) {
        val normalizedDisplayName = RoomRules.normalizeDisplayName(displayName)
            ?: throw IllegalArgumentException("Display name must be 2–24 characters.")
        require(host.status != com.localintercom.app.domain.model.RoomStatus.FULL) { "This room is full (6/6)." }
        require(sessionToken.isNotBlank()) { "Enter the room session token shared by the host." }
        require(RoomRules.isValidIpAddress(host.hostAddress) && RoomRules.isValidPort(host.port)) {
            "The selected room has an invalid network address."
        }
        repository.joinDiscoveredRoom(host, normalizedDisplayName, sessionToken.trim())
    }
}

class JoinManualRoomUseCase(private val repository: RoomRepository) {
    suspend operator fun invoke(hostAddress: String, port: Int, displayName: String, sessionToken: String) {
        val normalizedDisplayName = RoomRules.normalizeDisplayName(displayName)
            ?: throw IllegalArgumentException("Display name must be 2–24 characters.")
        require(RoomRules.isValidIpAddress(hostAddress)) { "Enter a valid IPv4 or IPv6 address." }
        require(RoomRules.isValidPort(port)) { "Port must be between 1 and 65535." }
        require(sessionToken.isNotBlank()) { "Enter the room session token shared by the host." }
        repository.joinManualRoom(hostAddress.trim().removePrefix("[").removeSuffix("]"), port, normalizedDisplayName, sessionToken.trim())
    }
}

class LeaveRoomUseCase(private val repository: RoomRepository) {
    suspend operator fun invoke() = repository.leaveRoom()
}
