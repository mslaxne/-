package com.local.wxbotprobe.memory.v2

import com.local.wxbotprobe.bot.PersonaData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemoryV2PersonaScopePolicyTest {
    @Test
    fun `same contact and persona resolve to one stable scope key`() {
        val first = resolve(
            contactId = CONTACT_A,
            personaId = PERSONA_A,
            persona = PersonaData("人格 A", emptyList()),
        )
        val second = resolve(
            contactId = CONTACT_A,
            personaId = PERSONA_A,
            persona = PersonaData("人格 A", emptyList()),
        )

        assertEquals(first.key, second.key)
    }

    @Test
    fun `different persona or contact produces a different scope key`() {
        val baseline = resolve(CONTACT_A, PERSONA_A, PersonaData("A", emptyList()))
        val otherPersona = resolve(
            CONTACT_A,
            PERSONA_B,
            PersonaData("B", emptyList()),
        )
        val otherContact = resolve(
            CONTACT_B,
            PERSONA_A,
            PersonaData("A", emptyList()),
        )

        assertNotEquals(baseline.key, otherPersona.key)
        assertNotEquals(baseline.key, otherContact.key)
        assertEquals(PERSONA_B, otherPersona.key.personaId)
    }

    @Test
    fun `persona revision changes do not change scope key`() {
        val original = resolve(
            CONTACT_A,
            PERSONA_A,
            PersonaData("旧正文", emptyList()),
        )
        val edited = resolve(
            CONTACT_A,
            PERSONA_A,
            PersonaData("新正文", listOf("你好" to "嗨")),
        )

        assertEquals(original.key, edited.key)
        assertNotEquals(original.personaRevision, edited.personaRevision)
    }

    private fun resolve(
        contactId: String,
        personaId: String,
        persona: PersonaData,
    ): ResolvedScopeInput {
        return MemoryV2ScopeKeyPolicy.resolve(
            ownerId = "owner",
            contactId = contactId,
            agentId = "agent",
            personaId = personaId,
            realmId = "real",
            persona = persona,
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
    }
}
