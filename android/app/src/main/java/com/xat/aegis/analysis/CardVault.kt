package com.xat.aegis.analysis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.xat.aegis.CardProfile
import com.xat.aegis.VaultCard
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted card credential store backed by Android Keystore (hardware TEE).
 *
 * Card data is encrypted with AES-256-GCM. The key never leaves the secure element —
 * no rooted tool, cloner, or Flipper Zero can extract it. The vault is accessible
 * whenever the phone is unlocked; call-site UI enforces biometric confirmation before
 * showing vault contents or activating emulation.
 */
class CardVault(private val context: Context) {

    private companion object {
        const val KEY_ALIAS  = "aegis_vault_v1"
        const val PREFS_NAME = "card_vault"
        const val PREFS_KEY  = "cards_enc"
        const val GCM_TAG_LEN = 128
    }

    init { ensureKey() }

    // ── Storage ─────────────────────────────────────────────────────────────

    fun load(): List<VaultCard> {
        val blob = prefs().getString(PREFS_KEY, null)
            ?.let { Base64.decode(it, Base64.DEFAULT) }
            ?: return emptyList()
        return try {
            val plain = decrypt(blob)
            val arr = JSONArray(String(plain, Charsets.UTF_8))
            (0 until arr.length()).map { deserialize(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun save(cards: List<VaultCard>) {
        val arr = JSONArray(cards.map { serialize(it) })
        val blob = encrypt(arr.toString().toByteArray(Charsets.UTF_8))
        prefs().edit()
            .putString(PREFS_KEY, Base64.encodeToString(blob, Base64.DEFAULT))
            .apply()
    }

    fun add(card: VaultCard)    = save(load().filter { it.uid != card.uid } + card)
    fun remove(id: String)      = save(load().filter { it.id != id })

    // ── Keystore ────────────────────────────────────────────────────────────

    private fun ensureKey() {
        val ks = ks()
        if (ks.containsAlias(KEY_ALIAS)) return
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Auth-required is enforced at the app layer (BiometricPrompt in the UI)
                // rather than on the key itself, so the key remains usable for background
                // sync and the unlock flow is controlled per-operation.
                .setUserAuthenticationRequired(false)
                .build()
        )
        kg.generateKey()
    }

    private fun secretKey(): SecretKey {
        val entry = ks().getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv           // 12 bytes, randomly generated
        val ct = cipher.doFinal(plain)
        return iv + ct               // [12-byte IV][ciphertext + 16-byte GCM tag]
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LEN, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private fun ks() = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── JSON ────────────────────────────────────────────────────────────────

    private fun serialize(c: VaultCard): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("uid", c.uid)
        put("label", c.label)
        put("profile", c.profile.name)
        put("addedTs", c.addedTs)
        put("pairs", JSONArray(c.apduPairs.map { (cmd, resp) ->
            JSONObject().put("c", cmd).put("r", resp)
        }))
    }

    private fun deserialize(o: JSONObject): VaultCard {
        val pairs = mutableListOf<Pair<String, String>>()
        val arr = o.optJSONArray("pairs")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                pairs += p.getString("c") to p.getString("r")
            }
        }
        return VaultCard(
            id         = o.getString("id"),
            uid        = o.getString("uid"),
            label      = o.getString("label"),
            profile    = runCatching { CardProfile.valueOf(o.getString("profile")) }
                             .getOrDefault(CardProfile.UNKNOWN),
            addedTs    = o.getLong("addedTs"),
            apduPairs  = pairs
        )
    }
}
