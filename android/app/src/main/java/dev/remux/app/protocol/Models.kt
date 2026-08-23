@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.remux.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

const val PROTOCOL_VERSION: Int = 1
const val MAX_WIRE_MESSAGE_BYTES: Int = 96 * 1024
const val MAX_TERMINAL_CHUNK_BYTES: Int = 32 * 1024

@Serializable
data class MachineInfo(
    val id: String,
    val name: String,
    val os: String,
    val arch: String,
    @SerialName("agent_version") val agentVersion: String,
)

@Serializable
data class SealedPayload(
    val nonce: String,
    val ciphertext: String,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface WireMessage {
    @Serializable
    @SerialName("client_hello")
    data class ClientHello(
        val protocol: Int,
        val token: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("client_name") val clientName: String,
    ) : WireMessage

    @Serializable
    @SerialName("ready")
    data class Ready(@SerialName("connection_id") val connectionId: String) : WireMessage

    @Serializable
    @SerialName("machine_snapshot")
    data class MachineSnapshot(val machines: List<MachineInfo>) : WireMessage

    @Serializable
    @SerialName("machine_online")
    data class MachineOnline(val machine: MachineInfo) : WireMessage

    @Serializable
    @SerialName("machine_offline")
    data class MachineOffline(@SerialName("machine_id") val machineId: String) : WireMessage

    @Serializable
    @SerialName("route_to_agent")
    data class RouteToAgent(
        @SerialName("machine_id") val machineId: String,
        val sealed: SealedPayload,
    ) : WireMessage

    @Serializable
    @SerialName("deliver_to_client")
    data class DeliverToClient(
        @SerialName("machine_id") val machineId: String,
        val sealed: SealedPayload,
    ) : WireMessage

    @Serializable
    @SerialName("ping")
    data class Ping(val nonce: ULong) : WireMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val nonce: ULong) : WireMessage

    @Serializable
    @SerialName("error")
    data class RelayError(
        val code: String,
        val message: String,
    ) : WireMessage
}

@Serializable
data class SessionInfo(
    val id: String,
    val name: String,
    val windows: UInt,
    @SerialName("attached_clients") val attachedClients: UInt,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("activity_at") val activityAt: Long,
)

@Serializable
data class WindowInfo(
    val id: String,
    val index: UInt,
    val name: String,
    val active: Boolean,
    val panes: UInt,
    val layout: String,
)

@Serializable
data class PaneInfo(
    val id: String,
    val index: UInt,
    val active: Boolean,
    val command: String,
    @SerialName("current_path") val currentPath: String,
    val width: UShort,
    val height: UShort,
)

@Serializable
enum class SizePolicy {
    @SerialName("auto")
    AUTO,

    @SerialName("preserve_existing")
    PRESERVE_EXISTING,

    @SerialName("take_control")
    TAKE_CONTROL,
}

@Serializable
@JsonClassDiscriminator("command")
sealed interface Command {
    @Serializable
    @SerialName("list_sessions")
    data object ListSessions : Command

    @Serializable
    @SerialName("list_windows")
    data class ListWindows(@SerialName("session_id") val sessionId: String) : Command

    @Serializable
    @SerialName("list_panes")
    data class ListPanes(@SerialName("window_id") val windowId: String) : Command

    @Serializable
    @SerialName("create_session")
    data class CreateSession(
        val name: String,
        val cwd: String?,
    ) : Command

    @Serializable
    @SerialName("rename_session")
    data class RenameSession(
        @SerialName("session_id") val sessionId: String,
        @SerialName("new_name") val newName: String,
    ) : Command

    @Serializable
    @SerialName("kill_session")
    data class KillSession(@SerialName("session_id") val sessionId: String) : Command

    @Serializable
    @SerialName("create_window")
    data class CreateWindow(
        @SerialName("session_id") val sessionId: String,
        val name: String?,
        val cwd: String?,
    ) : Command

    @Serializable
    @SerialName("rename_window")
    data class RenameWindow(
        @SerialName("window_id") val windowId: String,
        @SerialName("new_name") val newName: String,
    ) : Command

    @Serializable
    @SerialName("kill_window")
    data class KillWindow(@SerialName("window_id") val windowId: String) : Command

    @Serializable
    @SerialName("open_terminal")
    data class OpenTerminal(
        @SerialName("session_id") val sessionId: String,
        val cols: UShort,
        val rows: UShort,
        @SerialName("size_policy") val sizePolicy: SizePolicy,
    ) : Command
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ClientPayload {
    @Serializable
    @SerialName("request")
    data class Request(
        @SerialName("request_id") val requestId: String,
        val command: Command,
    ) : ClientPayload

    @Serializable
    @SerialName("terminal_input")
    data class TerminalInput(
        @SerialName("stream_id") val streamId: String,
        val data: String,
    ) : ClientPayload

    @Serializable
    @SerialName("terminal_resize")
    data class TerminalResize(
        @SerialName("stream_id") val streamId: String,
        val cols: UShort,
        val rows: UShort,
    ) : ClientPayload

    @Serializable
    @SerialName("terminal_refresh")
    data class TerminalRefresh(@SerialName("stream_id") val streamId: String) : ClientPayload

    @Serializable
    @SerialName("terminal_detach")
    data class TerminalDetach(@SerialName("stream_id") val streamId: String) : ClientPayload
}

@Serializable
@JsonClassDiscriminator("result")
sealed interface CommandResult {
    @Serializable
    @SerialName("sessions")
    data class Sessions(val sessions: List<SessionInfo>) : CommandResult

    @Serializable
    @SerialName("windows")
    data class Windows(val windows: List<WindowInfo>) : CommandResult

    @Serializable
    @SerialName("panes")
    data class Panes(val panes: List<PaneInfo>) : CommandResult

    @Serializable
    @SerialName("session_created")
    data class SessionCreated(val session: SessionInfo) : CommandResult

    @Serializable
    @SerialName("window_created")
    data class WindowCreated(val window: WindowInfo) : CommandResult

    @Serializable
    @SerialName("acknowledged")
    data object Acknowledged : CommandResult

    @Serializable
    @SerialName("terminal_opened")
    data class TerminalOpened(
        @SerialName("stream_id") val streamId: String,
        @SerialName("ignore_size") val ignoreSize: Boolean,
    ) : CommandResult
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface AgentPayload {
    @Serializable
    @SerialName("response")
    data class Response(
        @SerialName("request_id") val requestId: String,
        val result: CommandResult,
    ) : AgentPayload

    @Serializable
    @SerialName("request_error")
    data class RequestError(
        @SerialName("request_id") val requestId: String,
        val code: String,
        val message: String,
    ) : AgentPayload

    @Serializable
    @SerialName("terminal_output")
    data class TerminalOutput(
        @SerialName("stream_id") val streamId: String,
        val data: String,
    ) : AgentPayload

    @Serializable
    @SerialName("terminal_closed")
    data class TerminalClosed(
        @SerialName("stream_id") val streamId: String,
        val reason: String,
        @SerialName("exit_code") val exitCode: UInt?,
    ) : AgentPayload
}

@Serializable
data class PairingBundle(
    val version: Int,
    @SerialName("relay_url") val relayUrl: String,
    @SerialName("machine_id") val machineId: String,
    @SerialName("machine_name") val machineName: String,
    @SerialName("machine_secret") val machineSecret: String,
)
