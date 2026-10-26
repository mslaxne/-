package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryV2PersonaMigrationPolicyTest {
    @Test
    fun `same contact different persona is the only allowed scope pair`() {
        assertNull(
            MemoryV2MigrationPolicy.validateScopePair(
                source = key(CONTACT_A, PERSONA_A),
                target = key(CONTACT_A, PERSONA_B),
            ),
        )
        assertEquals(
            "CROSS_CONTACT_FORBIDDEN",
            MemoryV2MigrationPolicy.validateScopePair(
                source = key(CONTACT_A, PERSONA_A),
                target = key(CONTACT_B, PERSONA_B),
            ),
        )
        assertEquals(
            "SAME_PERSONA",
            MemoryV2MigrationPolicy.validateScopePair(
                source = key(CONTACT_A, PERSONA_A),
                target = key(CONTACT_A, PERSONA_A),
            ),
        )
    }

    @Test
    fun `owner agent or realm mismatch is rejected`() {
        assertEquals(
            "SCOPE_IDENTITY_MISMATCH",
            MemoryV2MigrationPolicy.validateScopePair(
                source = key(CONTACT_A, PERSONA_A, owner = "owner-a"),
                target = key(CONTACT_A, PERSONA_B, owner = "owner-b"),
            ),
        )
        assertEquals(
            "SCOPE_IDENTITY_MISMATCH",
            MemoryV2MigrationPolicy.validateScopePair(
                source = key(CONTACT_A, PERSONA_A, agent = "agent-a"),
                target = key(CONTACT_A, PERSONA_B, agent = "agent-b"),
            ),
        )
        assertEquals(
            "SCOPE_IDENTITY_MISMATCH",
            MemoryV2MigrationPolicy.validateScopePair(
                source = key(CONTACT_A, PERSONA_A, realm = "real"),
                target = key(CONTACT_A, PERSONA_B, realm = "test"),
            ),
        )
    }

    @Test
    fun `target memory id is deterministic and target scoped`() {
        val first = MemoryV2MigrationPolicy.targetMemoryId(
            SOURCE_SCOPE,
            MEMORY_ID,
            TARGET_SCOPE,
        )
        val second = MemoryV2MigrationPolicy.targetMemoryId(
            SOURCE_SCOPE,
            MEMORY_ID,
            TARGET_SCOPE,
        )
        val otherTarget = MemoryV2MigrationPolicy.targetMemoryId(
            SOURCE_SCOPE,
            MEMORY_ID,
            OTHER_TARGET_SCOPE,
        )

        assertEquals(first, second)
        assertNotEquals(first, otherTarget)
        assertEquals(true, first.startsWith("pm-mem-"))
    }

    @Test
    fun `rollback is allowed only after commit or for idempotent retry`() {
        assertEquals(true, MemoryV2MigrationPolicy.canRollback("committed"))
        assertEquals(true, MemoryV2MigrationPolicy.canRollback("rolled_back"))
        assertEquals(false, MemoryV2MigrationPolicy.canRollback("pending"))
        assertEquals(false, MemoryV2MigrationPolicy.canRollback("failed"))
    }

    private fun key(
        contactId: String,
        personaId: String,
        owner: String = "owner",
        agent: String = "agent",
        realm: String = "real",
    ): MemoryScopeKey {
        return MemoryScopeKey(
            ownerId = owner,
            contactId = contactId,
            agentId = agent,
            personaId = personaId,
            realmId = realm,
        )
    }

    companion object {
        private const val CONTACT_A =
            "11111111-1111-4111-8111-111111111111"
        private const val CONTACT_B =
            "22222222-2222-4222-8222-222222222222"
        private const val PERSONA_A =
            "33333333-3333-4333-8333-333333333333"
        private const val PERSONA_B =
            "44444444-4444-4444-8444-444444444444"
        private const val SOURCE_SCOPE =
            "55555555-5555-4555-8555-555555555555"
        private const val TARGET_SCOPE =
            "66666666-6666-4666-8666-666666666666"
        private const val OTHER_TARGET_SCOPE =
            "77777777-7777-4777-8777-777777777777"
        private const val MEMORY_ID =
            "88888888-8888-4888-8888-888888888888"
    }
}
