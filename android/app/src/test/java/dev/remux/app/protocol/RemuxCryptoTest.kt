package dev.remux.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemuxCryptoTest {
    private val machineId = "01890f5e-b080-7cc0-98d2-a0f9d1f43c01"
    private val clientId = "01890f5e-b080-7cc0-98d2-a0f9d1f43c02"
    private val streamId = "01890f5e-b080-7cc0-98d2-a0f9d1f43c03"
    private val secret = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { it.toByte() }

    @Test
    fun `matches protocol v1 ChaCha20 Poly1305 vector`() {
        val plaintext = ProtocolCodec.encodeClientPayload(
            ClientPayload.TerminalInput(
                streamId = streamId,
                data = "AAMD_w",
            ),
        )
        val sealed = RemuxCrypto.sealWithNonce(
            secret = secret,
            aad = RemuxCrypto.clientAad(machineId, clientId),
            plaintext = plaintext,
            nonce = nonce,
        )

        assertEquals("AAECAwQFBgcICQoL", sealed.nonce)
        assertEquals(
            "8tl8eVlyh3qV91qB9XRgAqUv24khAdmbyrVcsQelx1Gz6UjR3DyBqQ8LRr5Q8aJBTryZWcpL26s_geFjomL_5h9B2PyDIB-K9CVvkDoYvYW7_H0QLiu9elEg0nOpvdMVodn4UW4hba2Zi6T7",
            sealed.ciphertext,
        )
        assertArrayEquals(
            plaintext,
            RemuxCrypto.open(secret, RemuxCrypto.clientAad(machineId, clientId), sealed),
        )
    }

    @Test
    fun `rejects payload under wrong AAD`() {
        val sealed = RemuxCrypto.seal(secret, byteArrayOf(1), "payload".encodeToByteArray())
        assertThrows(SecurityException::class.java) {
            RemuxCrypto.open(secret, byteArrayOf(2), sealed)
        }
    }
}
