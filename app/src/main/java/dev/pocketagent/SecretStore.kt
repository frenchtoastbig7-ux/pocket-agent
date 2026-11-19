package dev.pocketagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets encrypted with an AES-256-GCM key that never leaves the Android Keystore (StrongBox / Titan M2 when
 * available): the Anthropic API key, and the bearer token Claude Code uses to reach the phone bridge.
 * Only ciphertext is written to app-private storage, and allowBackup=false keeps it out of device backups.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /**
     * Every read, write and key creation holds this: two first uses on different threads must not each create a
     * wrapping key (the second would orphan the first's ciphertext) or each mint a different bridge token.
     */
    private val lock = Any()

    fun hasApiKey(): Boolean = prefs.contains(API_KEY)
    fun putApiKey(value: String) = synchronized(lock) { put(API_KEY, value.trim()) }
    fun apiKey(): String? = synchronized(lock) { get(API_KEY) }
    fun clearApiKey() = prefs.edit().remove(API_KEY).apply()

    /** The bridge bearer token, created on first use. */
    fun bridgeToken(): String = synchronized(lock) { get(BRIDGE_TOKEN) ?: rotateBridgeToken() }

    /** Replaces the bridge token; Claude Code must be reconfigured with the new one. */
    fun rotateBridgeToken(): String = synchronized(lock) {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        put(BRIDGE_TOKEN, token)
        token
    }

    /** Deletes every secret and the Keystore key itself. */
    fun wipe(): Unit = synchronized(lock) {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, b64(cipher.iv) + ":" + b64(ciphertext)).apply()
    }

    private fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val (iv, ciphertext) = stored.split(":").takeIf { it.size == 2 }?.map { unb64(it) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        // StrongBox can fail in more ways than StrongBoxUnavailableException (a ProviderException, for one).
        return try {
            generate(strongBox = true)
        } catch (_: Exception) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "anthropic_api_key_wrap"
        const val API_KEY = "anthropic_api_key"
        const val BRIDGE_TOKEN = "bridge_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
