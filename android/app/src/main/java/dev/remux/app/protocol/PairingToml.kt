package dev.remux.app.protocol

import java.net.URI
import java.util.UUID
import kotlinx.serialization.decodeFromString

object PairingToml {
    private val assignment = Regex("^([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*(.+)$")
    private val expectedKeys = setOf(
        "version",
        "relay_url",
        "machine_id",
        "machine_name",
        "machine_secret",
    )

    fun parse(text: String): PairingBundle {
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, sourceLine ->
            val line = stripComment(sourceLine).trim()
            if (line.isEmpty()) return@forEachIndexed
            val match = assignment.matchEntire(line)
                ?: throw IllegalArgumentException("invalid pairing TOML at line ${index + 1}")
            val key = match.groupValues[1]
            require(key in expectedKeys) { "unknown pairing field '$key'" }
            require(values.put(key, match.groupValues[2].trim()) == null) {
                "duplicate pairing field '$key'"
            }
        }

        fun required(key: String): String = values[key]
            ?: throw IllegalArgumentException("missing pairing field '$key'")

        val version = required("version").toIntOrNull()
            ?: throw IllegalArgumentException("pairing version must be an integer")
        require(version == PROTOCOL_VERSION) { "unsupported pairing bundle version $version" }

        fun basicString(key: String): String = try {
            ProtocolCodec.json.decodeFromString(required(key))
        } catch (error: Exception) {
            throw IllegalArgumentException("pairing field '$key' must be a TOML basic string", error)
        }

        val relayUrl = basicString("relay_url")
        val relayUri = runCatching { URI(relayUrl) }
            .getOrElse { throw IllegalArgumentException("relay_url is invalid", it) }
        require(relayUri.scheme == "ws" || relayUri.scheme == "wss") {
            "relay_url must start with ws:// or wss://"
        }
        require(!relayUri.host.isNullOrBlank()) { "relay_url must include a host" }

        val machineId = runCatching { UUID.fromString(basicString("machine_id")).toString() }
            .getOrElse { throw IllegalArgumentException("machine_id is not a UUID", it) }
        val machineName = basicString("machine_name").trim()
        require(machineName.isNotEmpty()) { "machine_name cannot be empty" }
        val machineSecret = basicString("machine_secret")
        RemuxCrypto.decodeSecret(machineSecret)

        return PairingBundle(
            version = version,
            relayUrl = relayUrl.trimEnd('/'),
            machineId = machineId,
            machineName = machineName,
            machineSecret = machineSecret,
        )
    }

    private fun stripComment(line: String): String {
        var inString = false
        var escaped = false
        line.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                inString && character == '\\' -> escaped = true
                character == '"' -> inString = !inString
                character == '#' && !inString -> return line.substring(0, index)
            }
        }
        require(!inString) { "unterminated TOML string" }
        return line
    }
}
