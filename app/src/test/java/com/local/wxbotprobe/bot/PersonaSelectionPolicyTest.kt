package com.local.wxbotprobe.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PersonaSelectionPolicyTest {
    @Test
    fun `explicit active binding wins over default`() {
        assertEquals(
            PERSONA_B,
            PersonaSelectionPolicy.choosePersonaId(
                persistedPersonaId = PERSONA_B,
                defaultPersonaId = PERSONA_A,
                availablePersonaIds = setOf(PERSONA_A, PERSONA_B),
            ),
        )
    }

    @Test
    fun `missing or archived binding falls back to default`() {
        assertEquals(
            PERSONA_A,
            PersonaSelectionPolicy.choosePersonaId(
                persistedPersonaId = null,
                defaultPersonaId = PERSONA_A,
                availablePersonaIds = setOf(PERSONA_A),
            ),
        )
        assertEquals(
            PERSONA_A,
            PersonaSelectionPolicy.choosePersonaId(
                persistedPersonaId = PERSONA_B,
                defaultPersonaId = PERSONA_A,
                availablePersonaIds = setOf(PERSONA_A),
            ),
        )
    }

    @Test
    fun `binding key uses stable contact id not display name`() {
        val key = PersonaSelectionPolicy.preferenceKey(CONTACT_A)

        assertEquals(PersonaSelectionPolicy.PREFIX + CONTACT_A, key)
        assertNotEquals(
            key,
            PersonaSelectionPolicy.preferenceKey(CONTACT_B),
        )
        assertNotEquals(
            PersonaSelectionPolicy.preferenceKey("对方"),
            PersonaSelectionPolicy.preferenceKey("同事"),
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
