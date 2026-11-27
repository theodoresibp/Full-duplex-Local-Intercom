package com.localintercom.app.domain.model

data class IntercomChannel(
    val id: String,
    val name: String
)

object IntercomChannels {
    const val GENERAL = "general"
    const val TEAM_A = "team_a"
    const val TEAM_B = "team_b"
    const val SECURITY = "security"
    const val EMERGENCY = "emergency"

    val all = listOf(
        IntercomChannel(GENERAL, "General"),
        IntercomChannel(TEAM_A, "Team A"),
        IntercomChannel(TEAM_B, "Team B"),
        IntercomChannel(SECURITY, "Security"),
        IntercomChannel(EMERGENCY, "Emergency")
    )

    fun contains(id: String): Boolean = all.any { it.id == id }
}
