package com.local.wxbotprobe.memory.v2

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

class MemoryV2Crypto(
    private val alias: String = KEY_ALIAS,
) {
    fun hmac(label: String, value: String): String {
        val mac = Mac.getInstance(TRANSFORMATION)
        mac.init(getOrCreateKey())
        mac.update(label.toByteArray(Charsets.UTF_8))
        mac.update(0)
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "wxbot_memory_v2_hmac"
        private const val TRANSFORMATION = "HmacSHA256"

        fun sha256(value: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
