package dev.remux.app.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProtocolCodec {
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encodeWire(message: WireMessage): String =
        json.encodeToString(WireMessage.serializer(), message).also(::requireWireSize)

    fun decodeWire(text: String): WireMessage {
        requireWireSize(text)
        return json.decodeFromString(WireMessage.serializer(), text)
    }

    fun encodeClientPayload(payload: ClientPayload): ByteArray =
        json.encodeToString(ClientPayload.serializer(), payload).encodeToByteArray()

    fun decodeClientPayload(bytes: ByteArray): ClientPayload =
        json.decodeFromString(ClientPayload.serializer(), bytes.decodeToString())

    fun encodeAgentPayload(payload: AgentPayload): ByteArray =
        json.encodeToString(AgentPayload.serializer(), payload).encodeToByteArray()

    fun decodeAgentPayload(bytes: ByteArray): AgentPayload =
        json.decodeFromString(AgentPayload.serializer(), bytes.decodeToString())

    private fun requireWireSize(text: String) {
        require(text.encodeToByteArray().size <= MAX_WIRE_MESSAGE_BYTES) {
            "wire message exceeds maximum size"
        }
    }
}
