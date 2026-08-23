package dev.remux.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {
    @Test
    fun `client hello matches Rust wire format`() {
        val encoded = ProtocolCodec.encodeWire(
            WireMessage.ClientHello(
                protocol = 1,
                token = "0123456789abcdef",
                clientId = "01890f5e-b080-7cc0-98d2-a0f9d1f43c02",
                clientName = "android",
            ),
        )

        assertEquals(
            """{"type":"client_hello","protocol":1,"token":"0123456789abcdef","client_id":"01890f5e-b080-7cc0-98d2-a0f9d1f43c02","client_name":"android"}""",
            encoded,
        )
    }

    @Test
    fun `nested command uses its own discriminator`() {
        val encoded = ProtocolCodec.encodeClientPayload(
            ClientPayload.Request(
                requestId = "01890f5e-b080-7cc0-98d2-a0f9d1f43c04",
                command = Command.OpenTerminal(
                    sessionId = "\$0",
                    cols = 120u,
                    rows = 40u,
                    sizePolicy = SizePolicy.PRESERVE_EXISTING,
                ),
            ),
        ).decodeToString()

        assertEquals(
            """{"type":"request","request_id":"01890f5e-b080-7cc0-98d2-a0f9d1f43c04","command":{"command":"open_terminal","session_id":"${'$'}0","cols":120,"rows":40,"size_policy":"preserve_existing"}}""",
            encoded,
        )
    }

    @Test
    fun `decodes Rust terminal response`() {
        val payload = ProtocolCodec.decodeAgentPayload(
            """{"type":"terminal_closed","stream_id":"01890f5e-b080-7cc0-98d2-a0f9d1f43c03","reason":"tmux attach client exited","exit_code":0}""".encodeToByteArray(),
        )

        assertTrue(payload is AgentPayload.TerminalClosed)
        assertEquals(0u, (payload as AgentPayload.TerminalClosed).exitCode)
    }

    @Test
    fun `terminal refresh matches Rust v1 extension`() {
        val encoded = ProtocolCodec.encodeClientPayload(
            ClientPayload.TerminalRefresh("01890f5e-b080-7cc0-98d2-a0f9d1f43c03"),
        ).decodeToString()

        assertEquals(
            """{"type":"terminal_refresh","stream_id":"01890f5e-b080-7cc0-98d2-a0f9d1f43c03"}""",
            encoded,
        )
    }
}
