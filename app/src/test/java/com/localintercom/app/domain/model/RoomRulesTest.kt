package com.localintercom.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
