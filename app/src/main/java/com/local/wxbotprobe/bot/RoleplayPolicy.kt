package com.local.wxbotprobe.bot

import android.content.Context
import com.local.wxbotprobe.memory.v2.MemoryV2PersonaRevision
import org.json.JSONObject

enum class EffectiveRoleplayPolicy {
    NOT_APPLICABLE,
    TRANSPARENT,
    CONFIRMED_NONDISCLOSURE,
}

data class RoleplayConsentRecord(
    val contactId: String,
    val personaId: String,
    val personaRevision: Int,
    val confirmedAt: Long,
    val revokedAt: Long?,
) {
    fun isActiveFor(
        expectedPersonaId: String,
        expectedPersonaRevision: Int,
    ): Boolean {
        return revokedAt == null &&
            personaId == expectedPersonaId &&
            personaRevision == expectedPersonaRevision
    }
}

object RoleplayPolicyResolver {
    fun resolve(
        personaId: String,
        persona: PersonaData,
        consent: RoleplayConsentRecord?,
    ): EffectiveRoleplayPolicy {
        if (persona.identityMode != PersonaIdentityMode.IMMERSIVE_COMPANION) {
            return EffectiveRoleplayPolicy.NOT_APPLICABLE
        }
        val revision = MemoryV2PersonaRevision.of(persona)
        return if (
            consent?.isActiveFor(personaId, revision) == true
        ) {
            EffectiveRoleplayPolicy.CONFIRMED_NONDISCLOSURE
        } else {
            EffectiveRoleplayPolicy.TRANSPARENT
        }
    }
}

class RoleplayConsentStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun load(contactId: String): RoleplayConsentRecord? {
        val raw = preferences.getString(key(contactId), null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            RoleplayConsentRecord(
                contactId = contactId,
                personaId = root.getString("persona_id"),
                personaRevision = root.getInt("persona_revision"),
                confirmedAt = root.getLong("confirmed_at"),
                revokedAt = if (root.isNull("revoked_at")) {
                    null
                } else {
                    root.getLong("revoked_at")
                },
            )
        }.getOrNull()
    }

    @Synchronized
    fun confirm(
        contactId: String,
        personaId: String,
        personaRevision: Int,
        confirmedAt: Long,
    ): RoleplayConsentRecord {
        require(contactId.isNotBlank()) { "contactId is required" }
        require(personaId.isNotBlank()) { "personaId is required" }
        require(personaRevision > 0) { "personaRevision must be positive" }
        val record = RoleplayConsentRecord(
            contactId = contactId,
            personaId = personaId,
            personaRevision = personaRevision,
            confirmedAt = confirmedAt,
            revokedAt = null,
        )
        write(record)
        return record
    }

    @Synchronized
    fun revoke(
        contactId: String,
        revokedAt: Long,
    ): RoleplayConsentRecord? {
        val existing = load(contactId) ?: return null
        val revoked = existing.copy(revokedAt = revokedAt)
        write(revoked)
        return revoked
    }

    @Synchronized
    fun clear(contactId: String) {
        val key = key(contactId)
        if (preferences.contains(key)) {
            check(preferences.edit().remove(key).commit()) {
                "unable to clear roleplay consent"
            }
        }
    }

    @Synchronized
    fun clearAll() {
        check(preferences.edit().clear().commit()) {
            "unable to clear roleplay consent"
        }
    }

    @Synchronized
    fun records(): List<RoleplayConsentRecord> {
        return preferences.all.keys
            .filter { it.startsWith(PREFIX) }
            .mapNotNull { key ->
                load(key.removePrefix(PREFIX))
            }
            .sortedBy { it.contactId }
    }

    private fun write(record: RoleplayConsentRecord) {
        val value = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("persona_id", record.personaId)
            .put("persona_revision", record.personaRevision)
            .put("confirmed_at", record.confirmedAt)
            .put(
                "revoked_at",
                record.revokedAt ?: JSONObject.NULL,
            )
            .toString()
        check(
            preferences.edit()
                .putString(key(record.contactId), value)
                .commit(),
        ) { "unable to persist roleplay consent" }
    }

    private fun key(contactId: String): String {
        require(contactId.isNotBlank()) { "contactId is required" }
        return PREFIX + contactId
    }

    companion object {
        private const val PREFS = "memory_v2_roleplay_consent"
        private const val PREFIX = "consent:"
        private const val FORMAT_VERSION = 1
    }
}

class RoleplayPolicyService(
    context: Context,
) {
    private val consentStore = RoleplayConsentStore(context)

    fun effectivePolicy(
        contactId: String,
        personaId: String,
        persona: PersonaData,
    ): EffectiveRoleplayPolicy {
        return RoleplayPolicyResolver.resolve(
            personaId = personaId,
            persona = persona,
            consent = consentStore.load(contactId),
        )
    }

    fun consent(contactId: String): RoleplayConsentRecord? {
        return consentStore.load(contactId)
    }

    fun confirm(
        contactId: String,
        personaId: String,
        persona: PersonaData,
        confirmedAt: Long = System.currentTimeMillis(),
    ): RoleplayConsentRecord {
        if (persona.identityMode != PersonaIdentityMode.IMMERSIVE_COMPANION) {
            throw IllegalStateException("仅沉浸角色人格可以启用知情确认。")
        }
        return consentStore.confirm(
            contactId = contactId,
            personaId = personaId,
            personaRevision = MemoryV2PersonaRevision.of(persona),
            confirmedAt = confirmedAt,
        )
    }

    fun revoke(
        contactId: String,
        revokedAt: Long = System.currentTimeMillis(),
    ): RoleplayConsentRecord? {
        return consentStore.revoke(contactId, revokedAt)
    }

    fun clear(contactId: String) {
        consentStore.clear(contactId)
    }

    fun clearAll() {
        consentStore.clearAll()
    }
}
