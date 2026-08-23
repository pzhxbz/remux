package dev.remux.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayQuickConnectTest {
    @Test
    fun `parses relay emitted config line`() {
        val parsed = RelayQuickConnectParser.parse(
            """
                REMUX_APP_CONFIG=wss://relay.example.com:443/~Y2xpZW50L3Rva2VuIHdpdGggc3BhY2Vz
                This line contains the client credential; treat it as a secret.
            """.trimIndent(),
            defaultScheme = "ws",
        )

        assertEquals("wss://relay.example.com:443", parsed.relayUrl)
        assertEquals("client/token with spaces", parsed.clientToken)
    }

    @Test
    fun `parses terse server port secret input`() {
        val parsed = RelayQuickConnectParser.parse(
            "192.168.1.20:8787/android-client-token",
            defaultScheme = "ws",
        )

        assertEquals("ws://192.168.1.20:8787", parsed.relayUrl)
        assertEquals("android-client-token", parsed.clientToken)
    }

    @Test
    fun `rejects missing secret`() {
        assertThrows(IllegalArgumentException::class.java) {
            RelayQuickConnectParser.parse("relay.example.com:8787", defaultScheme = "wss")
        }
    }
}
