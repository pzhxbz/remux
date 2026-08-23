package dev.remux.app.data

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class RelayProfile(
    val name: String,
    val relayUrl: String,
    val clientToken: String,
)

@Serializable
data class AppConfig(
    val clientId: String,
    val clientName: String,
    val relay: RelayProfile? = null,
    val favoriteMachineIds: Set<String> = emptySet(),
    val recentMachineIds: List<String> = emptyList(),
    val tmuxPrefix: String = "C-b",
) {
    companion object {
        fun fresh(): AppConfig = AppConfig(
            clientId = UUID.randomUUID().toString(),
            clientName = "RemoteMux Android",
        )
    }
}
