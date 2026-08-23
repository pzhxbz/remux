package dev.remux.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    // Lenient codec for the locally persisted config only: older installs may
    // still carry fields removed in protocol v2 (e.g. "pairings"), which must
    // not kick the user back to the setup screen. ProtocolCodec.json stays
    // strict to protect the wire protocol.
    private val configJson = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    suspend fun load(): AppConfig = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(STATE_KEY, null) ?: return@withContext AppConfig.fresh()
        val plaintext = decrypt(decoder.decode(encoded))
        configJson.decodeFromString(AppConfig.serializer(), plaintext.decodeToString())
    }

    suspend fun save(config: AppConfig) = withContext(Dispatchers.IO) {
        val plaintext = configJson.encodeToString(AppConfig.serializer(), config).encodeToByteArray()
        val encoded = encoder.encodeToString(encrypt(plaintext))
        check(preferences.edit().putString(STATE_KEY, encoded).commit()) {
            "failed to persist encrypted app configuration"
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(CONFIG_AAD)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > GCM_IV_BYTES) { "encrypted app configuration is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, value.copyOfRange(0, GCM_IV_BYTES)),
        )
        cipher.updateAAD(CONFIG_AAD)
        return cipher.doFinal(value.copyOfRange(GCM_IV_BYTES, value.size))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "remux_secure_config"
        const val STATE_KEY = "encrypted_state_v1"
        const val KEY_ALIAS = "remux.config.v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        val CONFIG_AAD = "remux/android/config/v1".encodeToByteArray()
    }
}
