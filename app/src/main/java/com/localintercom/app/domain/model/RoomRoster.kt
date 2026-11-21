package com.localintercom.app.domain.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RoomRosterChange(
    val member: RoomMember,
    val members: List<RoomMember>
)

class RoomRoster(hostMember: RoomMember) {
    private val mutex = Mutex()
    private val members = mutableListOf(hostMember)

    init {
        require(hostMember.isHost) { "The initial room member must be the host." }
        require(hostMember.id.isNotBlank()) { "The host must have a member ID." }
        require(IntercomChannels.contains(hostMember.channelId)) { "The host channel is invalid." }
    }

    suspend fun tryAdd(member: RoomMember): RoomRosterChange? = mutex.withLock {
        if (member.isHost || member.id.isBlank() || members.any { it.id == member.id }) {
            return@withLock null
        }
        if (!RoomRules.canAcceptMember(members.size) || !IntercomChannels.contains(member.channelId)) {
            return@withLock null
        }

        members += member
        RoomRosterChange(member, members.toList())
    }

    suspend fun remove(memberId: String): RoomRosterChange? = mutex.withLock {
        val index = members.indexOfFirst { it.id == memberId && !it.isHost }
        if (index < 0) return@withLock null

        val removed = members.removeAt(index)
        RoomRosterChange(removed, members.toList())
    }

    suspend fun updateChannel(memberId: String, channelId: String): RoomRosterChange? = mutex.withLock {
        if (!IntercomChannels.contains(channelId)) return@withLock null
        val index = members.indexOfFirst { it.id == memberId }
        if (index < 0) return@withLock null
        val current = members[index]
        if (current.channelId == channelId) return@withLock null

        val updated = current.copy(channelId = channelId)
        members[index] = updated
        RoomRosterChange(updated, members.toList())
    }

    suspend fun snapshot(): List<RoomMember> = mutex.withLock { members.toList() }
}
