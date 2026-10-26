package com.local.wxbotprobe.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class ApiKeySummary(
    val id: String,
    val masked: String,
    val createdAt: Long,
    val active: Boolean,
    val providerId: String? = null,
)

object ApiKeyMasking {
    fun mask(apiKey: String): String {
        val value = apiKey.trim()
        if (value.isEmpty()) {
            return ""
        }
        if (value.length <= 8) {
            return "••••"
        }
        val prefix = if (value.startsWith("sk-")) "sk-" else "Key "
        return prefix + "••••" + value.takeLast(4)
    }
}

class ApiKeyStore(
    private val context: Context,
) {
    private data class StoredKey(
        val id: String,
        val ciphertext: String,
        val iv: String,
        val masked: String,
        val createdAt: Long,
        val providerId: String?,
    )

    private data class Vault(
        val activeId: String?,
        val keys: List<StoredKey>,
    )

    @Synchronized
    fun save(
        apiKey: String,
        providerId: String? = null,
    ): String? {
        val value = apiKey.trim()
        if (value.isEmpty()) {
            return null
        }
        val vault = readVault()
        vault.keys.firstOrNull { decrypt(it) == value }?.let { existing ->
            val updated = vault.keys.map {
                if (it.id == existing.id && it.providerId == null && providerId != null) {
                    it.copy(providerId = providerId)
                } else {
                    it
                }
            }
            writeVault(vault.copy(activeId = existing.id, keys = updated))
            return existing.id
        }
        val stored = encrypt(value, providerId)
        writeVault(
            Vault(
                activeId = stored.id,
                keys = vault.keys + stored,
            ),
        )
        return stored.id
    }

    @Synchronized
    fun load(providerId: String? = null): String? {
        val vault = readVault()
        val candidates = vault.keys.filter {
            providerId == null ||
                it.providerId == providerId ||
                (it.providerId == null && providerId == "deepseek")
        }
        val active = candidates.firstOrNull { it.id == vault.activeId }
        val ordered = listOfNotNull(active) +
            candidates.filterNot { it.id == active?.id }
        return ordered.firstNotNullOfOrNull(::decrypt)
    }

    @Synchronized
    fun summaries(providerId: String? = null): List<ApiKeySummary> {
        val vault = readVault()
        return vault.keys.filter {
            providerId == null ||
                it.providerId == providerId ||
                (it.providerId == null && providerId == "deepseek")
        }.map {
            ApiKeySummary(
                id = it.id,
                masked = it.masked,
                createdAt = it.createdAt,
                active = it.id == vault.activeId,
                providerId = it.providerId,
            )
        }
    }

    @Synchronized
    fun select(id: String): Boolean {
        val vault = readVault()
        if (vault.keys.none { it.id == id }) {
            return false
        }
        writeVault(vault.copy(activeId = id))
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val vault = readVault()
        if (vault.keys.none { it.id == id }) {
            return false
        }
        val remaining = vault.keys.filterNot { it.id == id }
        val activeId = when {
            remaining.isEmpty() -> null
            vault.activeId == id -> remaining.first().id
            else -> vault.activeId
        }
        writeVault(Vault(activeId = activeId, keys = remaining))
        return true
    }

    @Synchronized
    fun clear() {
        prefs().edit().clear().apply()
    }

    private fun readVault(): Vault {
        val raw = prefs().getString(KEY_VAULT, null)
        if (raw == null) {
            return migrateLegacy()
        }
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Vault(null, emptyList())
        val array = root.optJSONArray(KEY_KEYS) ?: JSONArray()
        val keys = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString(KEY_ID)
                val ciphertext = item.optString(KEY_CIPHERTEXT)
                val iv = item.optString(KEY_IV)
                if (id.isBlank() || ciphertext.isBlank() || iv.isBlank()) {
                    continue
                }
                add(
                    StoredKey(
                        id = id,
                        ciphertext = ciphertext,
                        iv = iv,
                        masked = item.optString(KEY_MASKED, "••••"),
                        createdAt = item.optLong(KEY_CREATED_AT),
                        providerId = item.optString(KEY_PROVIDER_ID)
                            .takeIf { value -> value.isNotBlank() },
                    ),
                )
            }
        }
        val configuredActive = root.optString(KEY_ACTIVE_ID)
            .takeIf { id -> keys.any { it.id == id } }
        return Vault(
            activeId = configuredActive ?: keys.firstOrNull()?.id,
            keys = keys,
        )
    }

    private fun migrateLegacy(): Vault {
        val ciphertext = prefs().getString(LEGACY_KEY_CIPHERTEXT, null)
            ?: return Vault(null, emptyList())
        val iv = prefs().getString(LEGACY_KEY_IV, null)
            ?: return Vault(null, emptyList())
        val plaintext = decryptValue(ciphertext, iv) ?: return Vault(null, emptyList())
        val stored = encrypt(plaintext, providerId = null)
        val vault = Vault(activeId = stored.id, keys = listOf(stored))
        writeVault(vault)
        return vault
    }

    private fun writeVault(vault: Vault) {
        val array = JSONArray()
        vault.keys.forEach { key ->
            array.put(
                JSONObject()
                    .put(KEY_ID, key.id)
                    .put(KEY_CIPHERTEXT, key.ciphertext)
                    .put(KEY_IV, key.iv)
                    .put(KEY_MASKED, key.masked)
                    .put(KEY_CREATED_AT, key.createdAt)
                    .put(KEY_PROVIDER_ID, key.providerId ?: ""),
            )
        }
        val root = JSONObject()
            .put(KEY_VERSION, VAULT_VERSION)
            .put(KEY_ACTIVE_ID, vault.activeId ?: "")
            .put(KEY_KEYS, array)
        prefs().edit()
            .putString(KEY_VAULT, root.toString())
            .remove(LEGACY_KEY_CIPHERTEXT)
            .remove(LEGACY_KEY_IV)
            .apply()
    }

    private fun encrypt(
        apiKey: String,
        providerId: String?,
    ): StoredKey {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        return StoredKey(
            id = UUID.randomUUID().toString(),
            ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            masked = ApiKeyMasking.mask(apiKey),
            createdAt = System.currentTimeMillis(),
            providerId = providerId,
        )
    }

    private fun decrypt(key: StoredKey): String? {
        return decryptValue(key.ciphertext, key.iv)
    }

    private fun decryptValue(ciphertext: String, iv: String): String? {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                Charsets.UTF_8,
            )
        }.getOrNull()
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "deepseek_credentials"
        private const val KEY_VAULT = "api_key_vault"
        private const val KEY_VERSION = "version"
        private const val KEY_ACTIVE_ID = "activeId"
        private const val KEY_KEYS = "keys"
        private const val KEY_ID = "id"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val KEY_IV = "iv"
        private const val KEY_MASKED = "masked"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_PROVIDER_ID = "providerId"
        private const val LEGACY_KEY_CIPHERTEXT = "api_key_ciphertext"
        private const val LEGACY_KEY_IV = "api_key_iv"
        private const val KEY_ALIAS = "wxbot_deepseek_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VAULT_VERSION = 2
    }
}
