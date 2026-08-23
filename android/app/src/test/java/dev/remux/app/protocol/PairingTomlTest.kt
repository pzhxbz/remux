package dev.remux.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingTomlTest {
    private val secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test
    fun `parses agent generated bundle`() {
        val pairing = PairingToml.parse(
            """
            version = 1
            relay_url = "wss://relay.example.test/base"
            machine_id = "01890f5e-b080-7cc0-98d2-a0f9d1f43c01"
            machine_name = "linux # build" # comment
            machine_secret = "$secret"
            """.trimIndent(),
        )

        assertEquals("linux # build", pairing.machineName)
        assertEquals("wss://relay.example.test/base", pairing.relayUrl)
    }

    @Test
    fun `rejects unknown fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingToml.parse(
                """
                version = 1
                relay_url = "wss://relay.example.test"
                machine_id = "01890f5e-b080-7cc0-98d2-a0f9d1f43c01"
                machine_name = "linux"
                machine_secret = "$secret"
                arbitrary_exec = "enabled"
                """.trimIndent(),
            )
        }
    }
}
