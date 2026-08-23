package dev.remux.app.protocol

import java.net.URI

data class RelayQuickConnect(
    val relayUrl: String,
    val clientToken: String,
)

object RelayQuickConnectParser {
    private const val OUTPUT_PREFIX = "REMUX_APP_CONFIG="

    fun parse(value: String, defaultScheme: String): RelayQuickConnect {
        require(defaultScheme == "ws" || defaultScheme == "wss") {
            "default Relay scheme must be ws or wss"
        }
        val pasted = value.trim()
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith(OUTPUT_PREFIX) }
            ?.removePrefix(OUTPUT_PREFIX)
            ?: value.trim()
        require(pasted.isNotEmpty()) { "Paste the REMUX_APP_CONFIG line from the Relay" }

        val withScheme = if ("://" in pasted) pasted else "$defaultScheme://$pasted"
        val uri = runCatching { URI(withScheme) }
            .getOrElse { throw IllegalArgumentException("Quick-connect value is not a valid address") }
        require(uri.scheme == "ws" || uri.scheme == "wss") {
            "Quick-connect address must use ws:// or wss://"
        }
        require(!uri.rawAuthority.isNullOrBlank() && !uri.host.isNullOrBlank()) {
            "Quick-connect address must include a server host"
        }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
            "Quick-connect address contains unsupported URL components"
        }
        val encodedToken = uri.rawPath.orEmpty().removePrefix("/")
        require(encodedToken.isNotEmpty() && '/' !in encodedToken) {
            "Quick-connect address must end with /secret"
        }
        val token = if (encodedToken.startsWith('~')) {
            runCatching {
                RemuxCrypto.decodeBase64Url(encodedToken.drop(1)).toString(Charsets.UTF_8)
            }.getOrElse { throw IllegalArgumentException("Quick-connect secret is invalid") }
        } else {
            encodedToken
        }
        require(token.length >= 16) { "Quick-connect secret must be at least 16 characters" }

        return RelayQuickConnect(
            relayUrl = "${uri.scheme}://${uri.rawAuthority}",
            clientToken = token,
        )
    }
}
