package com.xat.aegis.analysis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
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
 * Card data is encrypted with AES-256-GCM under a Keystore key that never leaves
 * secure hardware. The key itself requires user authentication: it can only be used
 * for [AUTH_VALIDITY_SECONDS] after the owner has confirmed their identity with a
 * strong biometric or the device PIN/pattern/password (BiometricPrompt in the UI,
 * or unlocking the phone). Without that, the Keystore refuses to decrypt — the gate
 * is enforced by the TEE, not merely by the screen that shows the cards.
 *
 * Nothing here throws. Every operation returns a result that distinguishes "there is
 * no vault yet" from "the vault exists but could not be read", and writes only ever
 * happen on top of a successful read, so a failed decryption can never be followed
 * by a save that replaces the stored cards with a shorter list.
 *
 * Migration: vaults written by earlier versions are encrypted under [KEY_ALIAS_V1],
 * a key that did not require authentication. The first successful unlock decrypts
 * that blob, re-encrypts it under [KEY_ALIAS_V2], commits it, and only then removes
 * the old blob and deletes the v1 key.
 */
class CardVault(private val context: Context) {

    /** Why the vault could not be read or written. */
    sealed interface Failure {
        /** The auth window has lapsed; authenticate again and retry. */
        data object AuthRequired : Failure
        /**
         * The key has been permanently invalidated (e.g. the screen lock was removed)
         * or is missing. The stored data can never be decrypted again.
         */
        data object KeyInvalidated : Failure
        /** The phone has no secure lock screen, so an auth-bound key cannot exist. */
        data object NoLockScreen : Failure
        /** The ciphertext or its contents are damaged, or the Keystore failed. */
        data class Error(val cause: Throwable) : Failure
    }

    sealed interface LoadResult {
        /** Decrypted successfully. An empty list means there is no vault yet. */
        data class Loaded(val cards: List<VaultCard>) : LoadResult
        data class Failed(val failure: Failure) : LoadResult
    }

    sealed interface WriteResult {
        data class Saved(val cards: List<VaultCard>) : WriteResult
        /** Nothing was written; the stored vault is exactly as it was. */
        data class Failed(val failure: Failure) : WriteResult
    }

    private companion object {
        const val KEY_ALIAS_V1 = "aegis_vault_v1"
        const val KEY_ALIAS_V2 = "aegis_vault_v2"
        const val PREFS_NAME = "card_vault"
        /** Blob encrypted under the v1 (no-auth) key. Present only before migration. */
        const val PREFS_KEY_V1 = "cards_enc"
        /** Blob encrypted under the v2 (auth-bound) key. */
        const val PREFS_KEY_V2 = "cards_enc_v2"
        const val GCM_TAG_LEN = 128
        const val IV_LEN = 12
        const val AUTH_VALIDITY_SECONDS = 30
    }

    /** True when there is stored vault data of either generation. */
    fun hasData(): Boolean = runCatching {
        val p = prefs()
        p.contains(PREFS_KEY_V2) || p.contains(PREFS_KEY_V1)
    }.getOrDefault(false)

    // ── Reads ───────────────────────────────────────────────────────────────

    /**
     * Decrypts the vault. Must be called within the auth window, i.e. just after a
     * successful BiometricPrompt. Migrates a v1 vault on the way.
     */
    fun load(): LoadResult = try {
        val p = prefs()
        val v2 = p.getString(PREFS_KEY_V2, null)
        val v1 = p.getString(PREFS_KEY_V1, null)
        when {
            v2 != null -> {
                val key = existingKey(KEY_ALIAS_V2)
                    ?: return LoadResult.Failed(Failure.KeyInvalidated)
                val cards = parse(decrypt(key, decode(v2)))
                // A migration that committed the v2 blob but was interrupted before
                // clearing the old generation; the v2 copy has just proved readable.
                if (v1 != null) discardV1()
                LoadResult.Loaded(cards)
            }
            v1 != null -> migrateFromV1(v1)
            else -> LoadResult.Loaded(emptyList())
        }
    } catch (e: Exception) {
        LoadResult.Failed(classify(e))
    }

    private fun migrateFromV1(blob: String): LoadResult {
        val oldKey = existingKey(KEY_ALIAS_V1)
            ?: return LoadResult.Failed(Failure.KeyInvalidated)
        val cards = parse(decrypt(oldKey, decode(blob)))
        // Re-encrypt under the auth-bound key and commit synchronously before any
        // trace of the old generation is removed: if anything below fails, the v1
        // blob and key are still intact and the next unlock simply tries again.
        val sealed = encrypt(v2KeyOrCreate(), serialize(cards))
        val committed = prefs().edit()
            .putString(PREFS_KEY_V2, encode(sealed))
            .commit()
        if (!committed) {
            return LoadResult.Failed(Failure.Error(IllegalStateException("Could not write migrated vault")))
        }
        discardV1()
        return LoadResult.Loaded(cards)
    }

    private fun discardV1() {
        prefs().edit().remove(PREFS_KEY_V1).commit()
        runCatching { ks().deleteEntry(KEY_ALIAS_V1) }
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /** Adds [card], replacing any stored card with the same UID. */
    fun add(card: VaultCard): WriteResult =
        modify { cards -> cards.filter { it.uid != card.uid } + card }

    fun remove(id: String): WriteResult =
        modify { cards -> cards.filter { it.id != id } }

    /**
     * Read, change, write — and only write if the read succeeded. The previous
     * version wrote `load() + card` where a failed load returned an empty list, so
     * one bad decryption replaced the whole vault with the single new card.
     */
    private fun modify(change: (List<VaultCard>) -> List<VaultCard>): WriteResult {
        val current = when (val r = load()) {
            is LoadResult.Loaded -> r.cards
            is LoadResult.Failed -> return WriteResult.Failed(r.failure)
        }
        val next = change(current)
        return try {
            val sealed = encrypt(v2KeyOrCreate(), serialize(next))
            val committed = prefs().edit()
                .putString(PREFS_KEY_V2, encode(sealed))
                .commit()
            if (committed) WriteResult.Saved(next)
            else WriteResult.Failed(Failure.Error(IllegalStateException("Could not write vault")))
        } catch (e: Exception) {
            WriteResult.Failed(classify(e))
        }
    }

    /**
     * Permanently deletes the stored vault and both keys. Only for the user's explicit
     * choice once the vault is unreadable (key invalidated or data damaged).
     */
    fun erase(): Boolean = runCatching {
        prefs().edit().remove(PREFS_KEY_V1).remove(PREFS_KEY_V2).commit()
        val ks = ks()
        if (ks.containsAlias(KEY_ALIAS_V1)) ks.deleteEntry(KEY_ALIAS_V1)
        if (ks.containsAlias(KEY_ALIAS_V2)) ks.deleteEntry(KEY_ALIAS_V2)
        true
    }.getOrDefault(false)

    // ── Keystore ────────────────────────────────────────────────────────────

    private fun existingKey(alias: String): SecretKey? {
        val ks = ks()
        if (!ks.containsAlias(alias)) return null
        return (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun v2KeyOrCreate(): SecretKey {
        existingKey(KEY_ALIAS_V2)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS_V2,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
                // Enrolling another fingerprint must not destroy the vault; the PIN
                // remains a valid way in either way.
                .setInvalidatedByBiometricEnrollment(false)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv           // 12 bytes, randomly generated
        val ct = cipher.doFinal(plain)
        return iv + ct               // [12-byte IV][ciphertext + 16-byte GCM tag]
    }

    private fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "Vault data truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, blob, 0, IV_LEN))
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    /**
     * Maps whatever the Keystore or the parser threw to a [Failure]. The Keystore
     * wraps some of these, so the cause chain is searched, not just the top level.
     */
    private fun classify(e: Throwable): Failure {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is UserNotAuthenticatedException -> return Failure.AuthRequired
                is KeyPermanentlyInvalidatedException -> return Failure.KeyInvalidated
            }
            // Generating an auth-bound key without a secure lock screen fails with
            // this message (InvalidAlgorithmParameterException / IllegalStateException).
            if (t.message?.contains("secure lock screen", ignoreCase = true) == true) {
                return Failure.NoLockScreen
            }
            t = t.cause
        }
        return Failure.Error(e)
    }

    private fun ks() = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.DEFAULT)
    private fun decode(text: String) = Base64.decode(text, Base64.DEFAULT)

    // ── JSON ────────────────────────────────────────────────────────────────

    private fun serialize(cards: List<VaultCard>): ByteArray =
        JSONArray(cards.map { toJson(it) }).toString().toByteArray(Charsets.UTF_8)

    private fun parse(plain: ByteArray): List<VaultCard> {
        val arr = JSONArray(String(plain, Charsets.UTF_8))
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    private fun toJson(c: VaultCard): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("uid", c.uid)
        put("label", c.label)
        put("profile", c.profile.name)
        put("addedTs", c.addedTs)
        put("pairs", JSONArray(c.apduPairs.map { (cmd, resp) ->
            JSONObject().put("c", cmd).put("r", resp)
        }))
    }

    private fun fromJson(o: JSONObject): VaultCard {
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
