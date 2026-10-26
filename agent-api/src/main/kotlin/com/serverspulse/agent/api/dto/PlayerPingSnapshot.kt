package com.serverspulse.agent.api.dto

data class PlayerPingSnapshot(
    val playerUuid: String,
    val ping: Int
)
