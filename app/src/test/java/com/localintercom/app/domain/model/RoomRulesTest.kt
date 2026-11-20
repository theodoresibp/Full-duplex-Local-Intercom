package com.localintercom.app.domain.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRulesTest {
    @Test
    fun displayNameIsTrimmedAndMustFitTheAllowedLength() {
        assertEquals("Theo", RoomRules.normalizeDisplayName("  Theo  "))
        assertEquals(null, RoomRules.normalizeDisplayName("A"))
        assertEquals(null, RoomRules.normalizeDisplayName("x".repeat(RoomRules.MAX_DISPLAY_NAME_LENGTH + 1)))
    }

    @Test
    fun roomNameIsTrimmedAndMustFitTheAllowedLength() {
        assertEquals("Study room", RoomRules.normalizeRoomName("  Study room "))
        assertEquals(null, RoomRules.normalizeRoomName("A"))
        assertEquals(null, RoomRules.normalizeRoomName("x".repeat(RoomRules.MAX_ROOM_NAME_LENGTH + 1)))
    }

    @Test
    fun hostCanAcceptAtMostFiveAdditionalMembers() {
        assertTrue(RoomRules.canAcceptMember(1))
        assertTrue(RoomRules.canAcceptMember(5))
        assertFalse(RoomRules.canAcceptMember(6))
        assertFalse(RoomRules.canAcceptMember(0))
    }

    @Test
    fun concurrentJoinAttemptsNeverExceedSixMembersIncludingHost() = runBlocking {
        val roster = RoomRoster(hostMember())
        val results = withContext(Dispatchers.Default) {
            (1..32).map { index ->
                async {
                    roster.tryAdd(RoomMember("member-$index", "Member $index", isHost = false))
                }
            }.awaitAll()
        }

        assertEquals(5, results.count { it != null })
        val members = roster.snapshot()
        assertEquals(RoomRules.MAX_MEMBERS, members.size)
        assertTrue(members.first().isHost)
    }

    @Test
    fun leavingMemberUpdatesSnapshotAndMakesCapacityAvailableAgain() = runBlocking {
        val roster = RoomRoster(hostMember())
        val joiningMember = RoomMember("member-1", "Theo", isHost = false)

        val afterJoin = roster.tryAdd(joiningMember)
        assertNotNull(afterJoin)
        assertEquals(listOf("host", "member-1"), afterJoin!!.members.map(RoomMember::id))

        val afterLeave = roster.remove(joiningMember.id)
        assertNotNull(afterLeave)
        assertEquals(joiningMember, afterLeave!!.member)
        assertEquals(listOf("host"), afterLeave.members.map(RoomMember::id))

        val replacement = RoomMember("member-2", "Guest", isHost = false)
        val afterReplacement = roster.tryAdd(replacement)
        assertNotNull(afterReplacement)
        assertEquals(listOf("host", "member-2"), afterReplacement!!.members.map(RoomMember::id))
    }

    @Test
    fun hostCannotBeRemovedAsAnOrdinaryMember() = runBlocking {
        val roster = RoomRoster(hostMember())

        assertNull(roster.remove("host"))
        assertEquals(listOf("host"), roster.snapshot().map(RoomMember::id))
    }

    @Test
    fun sessionTokenComparisonAcceptsOnlyTheExactToken() {
        assertTrue(RoomRules.isSessionTokenValid("secret-token", "secret-token"))
        assertFalse(RoomRules.isSessionTokenValid("secret-token", "wrong-token"))
        assertFalse(RoomRules.isSessionTokenValid("", ""))
    }

    @Test
    fun manualEndpointValidatesIpAndPort() {
        assertTrue(RoomRules.isValidIpAddress("192.168.1.12"))
        assertTrue(RoomRules.isValidIpAddress("2001:db8::1"))
        assertFalse(RoomRules.isValidIpAddress("192.168.1.999"))
        assertFalse(RoomRules.isValidIpAddress("intercom.local"))
        assertTrue(RoomRules.isValidPort(65_535))
        assertFalse(RoomRules.isValidPort(0))
        assertFalse(RoomRules.isValidPort(65_536))
    }

    private fun hostMember() = RoomMember("host", "Host", isHost = true)
}
