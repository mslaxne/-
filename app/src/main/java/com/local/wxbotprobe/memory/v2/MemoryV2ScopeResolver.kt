package com.local.wxbotprobe.memory.v2

import android.content.Context
import com.local.wxbotprobe.bot.PersonaData
import com.local.wxbotprobe.bot.PersonaProfileCodec
import com.local.wxbotprobe.memory.ChatIndexEntry
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

data class ResolvedScopeInput(
    val key: MemoryScopeKey,
    val personaRevision: Int,
)

class MemoryV2IdentityStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val ownerId: String
        get() = getOrCreate(KEY_OWNER)

    val agentId: String
        get() = getOrCreate(KEY_AGENT)

    val personaId: String
        get() = getOrCreate(KEY_PERSONA)

    val restoreGuardId: String
        get() = getOrCreate(KEY_RESTORE_GUARD)

    @Synchronized
    private fun getOrCreate(key: String): String {
        preferences.getString(key, null)?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        val value = UUID.randomUUID().toString()
        preferences.edit().putString(key, value).apply()
        return value
    }

    companion object {
        private const val PREFS = "memory_v2_identity"
        private const val KEY_OWNER = "owner_id"
        private const val KEY_AGENT = "agent_id"
        private const val KEY_PERSONA = "persona_id"
        private const val KEY_RESTORE_GUARD = "restore_guard_id"
    }
}

class MemoryV2ScopeResolver(
    private val identities: MemoryV2IdentityStore,
) {
    constructor(context: Context) : this(MemoryV2IdentityStore(context))

    fun resolve(
        contactId: String,
        personaId: String,
        persona: PersonaData,
        realmId: String = "real",
    ): ResolvedScopeInput {
        require(contactId.isNotBlank()) { "contactId is required" }
        require(personaId.isNotBlank()) { "personaId is required" }
        require(realmId.isNotBlank()) { "realmId is required" }
        return MemoryV2ScopeKeyPolicy.resolve(
            ownerId = identities.ownerId,
            contactId = contactId,
            agentId = identities.agentId,
            personaId = personaId,
            realmId = realmId,
            persona = persona,
        )
    }

    fun resolve(
        contact: ChatIndexEntry,
        personaId: String,
        persona: PersonaData,
        realmId: String = "real",
    ): ResolvedScopeInput {
        return resolve(
            contactId = contact.chatId,
            personaId = personaId,
            persona = persona,
            realmId = realmId,
        )
    }
}

object MemoryV2ScopeKeyPolicy {
    fun resolve(
        ownerId: String,
        contactId: String,
        agentId: String,
        personaId: String,
        realmId: String,
        persona: PersonaData,
    ): ResolvedScopeInput {
        require(ownerId.isNotBlank()) { "ownerId is required" }
        require(contactId.isNotBlank()) { "contactId is required" }
        require(agentId.isNotBlank()) { "agentId is required" }
        require(personaId.isNotBlank()) { "personaId is required" }
        require(realmId.isNotBlank()) { "realmId is required" }
        return ResolvedScopeInput(
            key = MemoryScopeKey(
                ownerId = ownerId,
                contactId = contactId,
                agentId = agentId,
                personaId = personaId,
                realmId = realmId,
            ),
            personaRevision = MemoryV2PersonaRevision.of(persona),
        )
    }
}

object MemoryV2PersonaRevision {
    fun of(persona: PersonaData): Int {
        val encoded = PersonaProfileCodec.encode(persona)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8))
        val value = ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int and Int.MAX_VALUE
        return value.coerceAtLeast(1)
    }
}

object MemoryV2RestoreFence {
    fun requireMatch(
        persistedGuardId: String,
        currentGuardId: String,
    ) {
        if (persistedGuardId != currentGuardId) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.RESTORE_FENCE_MISMATCH,
                "restored database does not match this installation",
            )
        }
    }
}
