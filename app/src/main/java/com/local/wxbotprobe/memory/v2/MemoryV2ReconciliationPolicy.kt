package com.local.wxbotprobe.memory.v2

object MemoryV2ReconciliationPolicy {
    const val ADD = "ADD"
    const val CORROBORATE = "CORROBORATE"
    const val SUPERSEDE = "SUPERSEDE"
    const val CONTRADICT = "CONTRADICT"
    const val RELATE = "RELATE"
    const val RETRACT = "RETRACT"
    const val NOOP = "NOOP"

    val operations = listOf(
        ADD,
        CORROBORATE,
        SUPERSEDE,
        CONTRADICT,
        RELATE,
        RETRACT,
        NOOP,
    )

    fun normalize(raw: String?): String? {
        return when (raw?.trim()?.uppercase()) {
            null, "" -> ADD
            "ADD", "INSERT", "CREATE" -> ADD
            "CORROBORATE", "CONFIRM", "SUPPORT" -> CORROBORATE
            "SUPERSEDE", "UPDATE", "REPLACE" -> SUPERSEDE
            "CONTRADICT", "CONFLICT", "REVIEW" -> CONTRADICT
            "RELATE", "LINK", "ASSOCIATE" -> RELATE
            "RETRACT", "WITHDRAW", "INVALIDATE" -> RETRACT
            "NOOP", "NO_OP", "UNCHANGED" -> NOOP
            else -> null
        }
    }

    fun canonical(raw: String?): String? {
        return raw?.trim()?.uppercase()?.takeIf { it in operations }
    }

    fun requiresTarget(operation: String): Boolean {
        return operation != ADD
    }

    fun validateTarget(
        operation: String,
        targetMemoryId: String?,
        expectedVersion: Int?,
    ): MemoryV2ErrorCode? {
        val targetPresent = !targetMemoryId.isNullOrBlank()
        val versionPresent = expectedVersion != null && expectedVersion > 0
        return when {
            operation == ADD && (targetPresent || versionPresent) ->
                MemoryV2ErrorCode.SCHEMA_INVALID
            requiresTarget(operation) && !targetPresent ->
                MemoryV2ErrorCode.SCHEMA_INVALID
            requiresTarget(operation) && !versionPresent ->
                MemoryV2ErrorCode.SCHEMA_INVALID
            else -> null
        }
    }

    fun mutatesMemory(operation: String): Boolean {
        return operation in setOf(
            CORROBORATE,
            SUPERSEDE,
            RETRACT,
        )
    }

    fun normalizeRelationType(raw: String?): String? {
        return when (raw?.trim()?.uppercase()) {
            "SUPPORT", "SUPPORTS", "CORROBORATE", "CORROBORATES" -> "SUPPORTS"
            "ELABORATE", "ELABORATES", "CONTEXT", "RELATED" -> "ELABORATES"
            else -> null
        }
    }

    fun canonicalRelationType(raw: String?): String? {
        return raw?.trim()?.uppercase()?.takeIf {
            it in setOf("SUPPORTS", "ELABORATES")
        }
    }
}
