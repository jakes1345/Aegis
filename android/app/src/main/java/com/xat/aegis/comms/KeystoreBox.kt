package com.xat.aegis.comms

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under an Android Keystore key for everything the comms module
 * keeps on disk: the relay bearer token and the bodies of cached messages.
 *
 * Unlike the card vault's key this one does not require user authentication:
 * the push service has to decrypt and display a message with the phone in a
 * pocket. What it buys is that the token and the conversation cannot be read
 * from a backup or by anything that copies the app's files off the device; the
 * key itself never leaves secure hardware.
 */
object CommsCrypto {

    private const val KEY_ALIAS = "aegis_comms_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "ciphertext truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    fun encryptString(text: String): ByteArray = encrypt(text.toByteArray(Charsets.UTF_8))

    fun decryptString(blob: ByteArray): String = String(decrypt(blob), Charsets.UTF_8)

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Deletes the key; every blob encrypted under it becomes unreadable. Used on unpair. */
    @Synchronized
    fun destroy() {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }
}
