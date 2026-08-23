package dev.remux.app.network

import dev.remux.app.data.RelayProfile
import dev.remux.app.protocol.AgentPayload
import dev.remux.app.protocol.ClientPayload
import dev.remux.app.protocol.Command
import dev.remux.app.protocol.CommandResult
import dev.remux.app.protocol.PairingBundle
import dev.remux.app.protocol.ProtocolCodec
import dev.remux.app.protocol.RemuxCrypto
import dev.remux.app.protocol.SealedPayload
import dev.remux.app.protocol.SessionInfo
import dev.remux.app.protocol.WireMessage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayClientTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val machineId = "01890f5e-b080-7cc0-98d2-a0f9d1f43c01"
    private val clientId = "01890f5e-b080-7cc0-98d2-a0f9d1f43c02"
    private val secret = ByteArray(32) { it.toByte() }
    private lateinit var relayUrl: String
    private lateinit var pairing: PairingBundle

    @Before
    fun setUp() {
        relayUrl = server.url("/").toString().replaceFirst("http", "ws").trimEnd('/')
        pairing = PairingBundle(
            version = 1,
            relayUrl = relayUrl,
            machineId = machineId,
            machineName = "linux",
            machineSecret = RemuxCrypto.encodeBase64Url(secret),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun `handshake and encrypted request match relay protocol`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeRelay()))
        val client = RelayClient(
            profile = RelayProfile("test", relayUrl, "0123456789abcdef"),
            clientId = clientId,
            clientName = "android-test",
            initialPairings = listOf(pairing),
            scope = scope,
        )

        client.awaitConnected()
        val result = client.request(pairing, Command.ListSessions)

        assertTrue(result is CommandResult.Sessions)
        assertEquals("codex", (result as CommandResult.Sessions).sessions.single().name)
        client.close()
    }

    private inner class FakeRelay : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val wire = ProtocolCodec.decodeWire(text)) {
                is WireMessage.ClientHello -> {
                    assertEquals(clientId, wire.clientId)
                    webSocket.send(
                        ProtocolCodec.encodeWire(WireMessage.Ready(UUID.randomUUID().toString())),
                    )
                    webSocket.send(ProtocolCodec.encodeWire(WireMessage.MachineSnapshot(emptyList())))
                }
                is WireMessage.RouteToAgent -> handleRequest(webSocket, wire.sealed)
                is WireMessage.Ping -> webSocket.send(
                    ProtocolCodec.encodeWire(WireMessage.Pong(wire.nonce)),
                )
                else -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            throw AssertionError("mock relay websocket failed", error)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        private fun handleRequest(webSocket: WebSocket, sealed: SealedPayload) {
            val request = ProtocolCodec.decodeClientPayload(
                RemuxCrypto.open(secret, RemuxCrypto.clientAad(machineId, clientId), sealed),
            ) as ClientPayload.Request
            assertTrue(request.command is Command.ListSessions)
            val response = AgentPayload.Response(
                requestId = request.requestId,
                result = CommandResult.Sessions(
                    listOf(
                        SessionInfo(
                            id = "\$0",
                            name = "codex",
                            windows = 1u,
                            attachedClients = 0u,
                            createdAt = 1,
                            activityAt = 2,
                        ),
                    ),
                ),
            )
            webSocket.send(
                ProtocolCodec.encodeWire(
                    WireMessage.DeliverToClient(
                        machineId = machineId,
                        sealed = RemuxCrypto.seal(
                            secret,
                            RemuxCrypto.agentAad(machineId, clientId),
                            ProtocolCodec.encodeAgentPayload(response),
                        ),
                    ),
                ),
            )
        }
    }
}
