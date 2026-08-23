package dev.remux.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemuxCryptoTest {
    @Test
    fun `base64url round trip without padding`() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = RemuxCrypto.encodeBase64Url(bytes)

        assertArrayEquals(bytes, RemuxCrypto.decodeBase64Url(encoded))
    }

    @Test
    fun `rejects invalid base64url`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemuxCrypto.decodeBase64Url("not valid base64url!!!")
        }
    }
}
