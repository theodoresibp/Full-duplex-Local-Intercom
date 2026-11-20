package com.localintercom.app.domain.model

import java.security.MessageDigest

object RoomRules {
    const val MAX_MEMBERS = 6
    const val MIN_DISPLAY_NAME_LENGTH = 2
    const val MAX_DISPLAY_NAME_LENGTH = 24
    const val MIN_ROOM_NAME_LENGTH = 2
    const val MAX_ROOM_NAME_LENGTH = 32

    fun normalizeDisplayName(value: String): String? =
        value.trim().takeIf { it.length in MIN_DISPLAY_NAME_LENGTH..MAX_DISPLAY_NAME_LENGTH }

    fun normalizeRoomName(value: String): String? =
        value.trim().takeIf { it.length in MIN_ROOM_NAME_LENGTH..MAX_ROOM_NAME_LENGTH }

    fun canAcceptMember(currentMemberCount: Int): Boolean =
        currentMemberCount in 1 until MAX_MEMBERS

    fun isSessionTokenValid(expected: String, provided: String): Boolean =
        expected.isNotBlank() &&
            provided.isNotBlank() &&
            MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                provided.toByteArray(Charsets.UTF_8)
            )

    fun isValidPort(port: Int): Boolean = port in 1..65_535

    fun isValidIpAddress(address: String): Boolean {
        val value = address.trim().removePrefix("[").removeSuffix("]")
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        return if (value.contains(':')) {
            runCatching { java.net.InetAddress.getByName(value) is java.net.Inet6Address }.getOrDefault(false)
        } else {
            val parts = value.split('.')
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() &&
                    (part.length == 1 || !part.startsWith('0')) &&
                    part.all(Char::isDigit) &&
                    part.toIntOrNull() in 0..255
            }
        }
    }
}
