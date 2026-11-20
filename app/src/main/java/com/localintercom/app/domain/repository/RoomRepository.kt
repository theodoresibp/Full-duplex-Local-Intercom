package com.localintercom.app.domain.repository

import com.localintercom.app.domain.model.DiscoveredRoom
import com.localintercom.app.domain.model.DiscoveryState
import com.localintercom.app.domain.model.RoomSession
import kotlinx.coroutines.flow.StateFlow

interface RoomRepository {
    val activeRoom: StateFlow<RoomSession?>
    val discoveredRooms: StateFlow<List<DiscoveredRoom>>
    val discoveryState: StateFlow<DiscoveryState>

    suspend fun createRoom(roomName: String, displayName: String)
    fun refreshDiscovery()
    suspend fun joinDiscoveredRoom(room: DiscoveredRoom, displayName: String, sessionToken: String)
    suspend fun joinManualRoom(
        hostAddress: String,
        port: Int,
        displayName: String,
        sessionToken: String
    )
    suspend fun leaveRoom()
    fun releaseWhenUiCloses()
}
