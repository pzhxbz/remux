package dev.remux.app.protocol

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object RemuxCrypto {
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun decodeSecret(value: String): ByteArray = decodeBase64Url(value).also {
        require(it.size == KEY_BYTES) { "machine secret must be exactly 32 bytes" }
    }

    fun encodeBase64Url(value: ByteArray): String = encoder.encodeToString(value)

    fun decodeBase64Url(value: String): ByteArray = try {
        decoder.decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("value is not valid base64url", error)
    }

    fun seal(secret: ByteArray, aad: ByteArray, plaintext: ByteArray): SealedPayload {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return sealWithNonce(secret, aad, plaintext, nonce)
    }

    internal fun sealWithNonce(
        secret: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
        nonce: ByteArray,
    ): SealedPayload {
        require(secret.size == KEY_BYTES) { "machine secret must be exactly 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be exactly 12 bytes" }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(secret, "ChaCha20"),
            IvParameterSpec(nonce),
        )
        cipher.updateAAD(aad)
        return SealedPayload(
            nonce = encodeBase64Url(nonce),
            ciphertext = encodeBase64Url(cipher.doFinal(plaintext)),
        )
    }

    fun open(secret: ByteArray, aad: ByteArray, sealed: SealedPayload): ByteArray {
        require(secret.size == KEY_BYTES) { "machine secret must be exactly 32 bytes" }
        val nonce = decodeBase64Url(sealed.nonce)
        require(nonce.size == NONCE_BYTES) { "nonce must be exactly 12 bytes" }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(secret, "ChaCha20"),
            IvParameterSpec(nonce),
        )
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(decodeBase64Url(sealed.ciphertext))
        } catch (error: AEADBadTagException) {
            throw SecurityException("payload authentication failed", error)
        }
    }

    fun clientAad(machineId: String, clientId: String): ByteArray =
        "remux/v1/client-to-agent/$machineId/$clientId".encodeToByteArray()

    fun agentAad(machineId: String, clientId: String): ByteArray =
        "remux/v1/agent-to-client/$machineId/$clientId".encodeToByteArray()
}
