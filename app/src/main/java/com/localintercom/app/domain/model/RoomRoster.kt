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
    }

    suspend fun tryAdd(member: RoomMember): RoomRosterChange? = mutex.withLock {
        if (member.isHost || member.id.isBlank() || members.any { it.id == member.id }) {
            return@withLock null
        }
        if (!RoomRules.canAcceptMember(members.size)) return@withLock null

        members += member
        RoomRosterChange(member, members.toList())
    }

    suspend fun remove(memberId: String): RoomRosterChange? = mutex.withLock {
        val index = members.indexOfFirst { it.id == memberId && !it.isHost }
        if (index < 0) return@withLock null

        val removed = members.removeAt(index)
        RoomRosterChange(removed, members.toList())
    }

    suspend fun snapshot(): List<RoomMember> = mutex.withLock { members.toList() }
}
