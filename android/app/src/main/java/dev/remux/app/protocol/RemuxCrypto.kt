package dev.remux.app.protocol

import java.util.Base64

object RemuxCrypto {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encodeBase64Url(value: ByteArray): String = encoder.encodeToString(value)

    fun decodeBase64Url(value: String): ByteArray = try {
        decoder.decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("value is not valid base64url", error)
    }
}
