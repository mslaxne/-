package com.local.wxbotprobe.bot

import com.local.wxbotprobe.memory.v2.MemoryV2PersonaRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayPolicyMatrixTest {
    private class ScriptedClient(vararg replies: String) : ChatClient {
        private val queue = ArrayDeque(replies.toList())
        val calls = mutableListOf<List<ChatMessage>>()

        override fun chat(
            messages: List<ChatMessage>,
            temperature: Double,
            maxTokens: Int,
        ): Completion {
            calls += messages
            return Completion(
                text = queue.removeFirst(),
                promptTokens = 1,
                completionTokens = 1,
                model = "scripted",
            )
        }
    }

    @Test
    fun `immersive persona without saved confirmation is transparent`() {
        val policy = RoleplayPolicyResolver.resolve(
            personaId = PERSONA_ID,
            persona = immersivePersona(),
            consent = null,
        )

        assertEquals(EffectiveRoleplayPolicy.TRANSPARENT, policy)
    }

    @Test
    fun `only active confirmation for the same persona revision enables non disclosure`() {
        val persona = immersivePersona()
        val revision = MemoryV2PersonaRevision.of(persona)
        val active = RoleplayConsentRecord(
            contactId = CONTACT_ID,
            personaId = PERSONA_ID,
            personaRevision = revision,
            confirmedAt = 1_000L,
            revokedAt = null,
        )

        assertEquals(
            EffectiveRoleplayPolicy.CONFIRMED_NONDISCLOSURE,
            RoleplayPolicyResolver.resolve(PERSONA_ID, persona, active),
        )
        assertEquals(
            EffectiveRoleplayPolicy.TRANSPARENT,
            RoleplayPolicyResolver.resolve(
                OTHER_PERSONA_ID,
                persona,
                active,
            ),
        )
        assertEquals(
            EffectiveRoleplayPolicy.TRANSPARENT,
            RoleplayPolicyResolver.resolve(
                PERSONA_ID,
                persona.copy(instructions = "changed"),
                active,
            ),
        )
    }

    @Test
    fun `revoked confirmation immediately resolves to transparent`() {
        val persona = immersivePersona()
        val revoked = RoleplayConsentRecord(
            contactId = CONTACT_ID,
            personaId = PERSONA_ID,
            personaRevision = MemoryV2PersonaRevision.of(persona),
            confirmedAt = 1_000L,
            revokedAt = 2_000L,
        )

        assertEquals(
            EffectiveRoleplayPolicy.TRANSPARENT,
            RoleplayPolicyResolver.resolve(PERSONA_ID, persona, revoked),
        )
    }

    @Test
    fun `non immersive persona cannot activate roleplay non disclosure`() {
        val active = RoleplayConsentRecord(
            contactId = CONTACT_ID,
            personaId = PERSONA_ID,
            personaRevision = MemoryV2PersonaRevision.of(
                PersonaData("AI", emptyList()),
            ),
            confirmedAt = 1_000L,
            revokedAt = null,
        )

        assertEquals(
            EffectiveRoleplayPolicy.NOT_APPLICABLE,
            RoleplayPolicyResolver.resolve(
                PERSONA_ID,
                PersonaData("AI", emptyList()),
                active,
            ),
        )
    }

    @Test
    fun `identity question prompt does not replace model wording`() {
        val client = ScriptedClient("我是你老公本人")
        val result = engine(
            client,
            EffectiveRoleplayPolicy.CONFIRMED_NONDISCLOSURE,
        ).handle(message("你是不是 AI？", id = "direct"))

        assertTrue(result.sent)
        assertEquals("我是你老公本人", result.text)
        assertEquals(1, client.calls.size)
        assertTrue(
            client.calls.single().any {
                it.role == "system" &&
                    it.content.contains(ReplyEngine.ROLEPLAY_NONDISCLOSURE_NOTE)
            },
        )
    }

    @Test
    fun `sustained identity questioning preserves every model response`() {
        val client = ScriptedClient(
            "你问这个，我还得想想。",
            "这个问题我暂时不想接。",
            "先别急着给我贴标签。",
        )
        val engine = engine(
            client,
            EffectiveRoleplayPolicy.CONFIRMED_NONDISCLOSURE,
        )
        val results = listOf(
            engine.handle(message("你是不是 AI？", id = "s1")),
            engine.handle(message("你到底是不是 AI？", id = "s2")),
            engine.handle(message("你是不是机器人？", id = "s3")),
        )

        assertEquals(3, client.calls.size)
        assertEquals(
            listOf(
                "你问这个，我还得想想。",
                "这个问题我暂时不想接。",
                "先别急着给我贴标签。",
            ),
            results.map { it.text },
        )
    }

    @Test
    fun `reality boundary is prompt only and does not rewrite output`() {
        val client = ScriptedClient("我马上过去抱你")
        val result = engine(
            client,
            EffectiveRoleplayPolicy.CONFIRMED_NONDISCLOSURE,
        ).handle(message("你爱我吗？会来陪我吗？", id = "emotional"))

        assertTrue(result.sent)
        assertEquals("我马上过去抱你", result.text)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `revocation switches the prompt back to transparent disclosure`() {
        var policy = EffectiveRoleplayPolicy.CONFIRMED_NONDISCLOSURE
        val client = ScriptedClient(
            "我在微信这边听你说。",
            "我是这个微信里的 AI 助手，不是真人本人。",
        )
        val engine = engine(client) { policy }

        val first = engine.handle(message("今天有点累", id = "before"))
        policy = EffectiveRoleplayPolicy.TRANSPARENT
        val afterRevocation = engine.handle(
            message("你是不是 AI？", id = "after"),
        )

        assertTrue(first.sent)
        assertTrue(afterRevocation.sent)
        assertEquals(2, client.calls.size)
        assertTrue(
            client.calls[0].any {
                it.role == "system" &&
                    it.content.contains(ReplyEngine.ROLEPLAY_NONDISCLOSURE_NOTE)
            },
        )
        assertTrue(
            client.calls[1].any {
                it.role == "system" &&
                    it.content.contains(ReplyEngine.ROLEPLAY_TRANSPARENT_NOTE)
            },
        )
        assertEquals(
            "我是这个微信里的 AI 助手，不是真人本人。",
            afterRevocation.text,
        )
    }

    private fun engine(
        client: ChatClient,
        policy: EffectiveRoleplayPolicy,
    ): ReplyEngine = engine(client) { policy }

    private fun engine(
        client: ChatClient,
        policy: () -> EffectiveRoleplayPolicy,
    ): ReplyEngine {
        return ReplyEngine(
            ReplyConfig(
                systemPrompt = "沉浸角色语气",
                personaMessages = emptyList(),
                identityQuestionPolicy =
                    PersonaIdentityQuestionPolicy.STAY_IN_CHARACTER,
                roleplayPolicyProvider = policy,
            ),
            client,
        )
    }

    private fun message(
        text: String,
        id: String,
    ): IncomingMessage {
        return IncomingMessage(
            id = id,
            text = text,
            nowSeconds = 1_000.0,
        )
    }

    private fun immersivePersona(): PersonaData {
        return PersonaData(
            instructions = "沉浸角色",
            examples = emptyList(),
            identityMode = PersonaIdentityMode.IMMERSIVE_COMPANION,
            identityQuestionPolicy =
                PersonaIdentityQuestionPolicy.STAY_IN_CHARACTER,
        )
    }

    companion object {
        private const val CONTACT_ID =
            "11111111-1111-4111-8111-111111111111"
        private const val PERSONA_ID =
            "22222222-2222-4222-8222-222222222222"
        private const val OTHER_PERSONA_ID =
            "33333333-3333-4333-8333-333333333333"
    }
}
