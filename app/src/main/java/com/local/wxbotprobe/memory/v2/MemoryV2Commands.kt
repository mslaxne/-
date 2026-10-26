package com.local.wxbotprobe.memory.v2

data class MemoryCommandEvidence(
    val messageId: String,
    val sourceRevision: Int,
    val quote: String,
)

data class SaveMemoryCommand(
    val operationId: String,
    val commandSeq: Int,
    val tier: String,
    val predicate: String,
    val subjectId: String,
    val viewpointId: String?,
    val text: String,
    val value: String,
    val epistemic: String = "declared",
    val evidence: List<MemoryCommandEvidence>,
    val memoryId: String? = null,
    val expectedVersion: Int? = null,
    val eventId: String? = null,
    val pinned: Boolean = false,
    val commitmentState: String? = null,
    val coreAuthorized: Boolean = false,
    val personaEditor: Boolean = false,
    val relearn: Boolean = false,
    val allowConflictResolution: Boolean = false,
    val temporalJson: String = EMPTY_TEMPORAL,
) {
    companion object {
        const val EMPTY_TEMPORAL =
            """{"occurred_at":null,"local_date":null,"timezone":"Asia/Shanghai","precision":"unknown","original":""}"""
    }
}

data class PinMemoryCommand(
    val operationId: String,
    val commandSeq: Int,
    val memoryId: String,
    val expectedVersion: Int,
    val pinned: Boolean,
)

data class ForgetMemoryCommand(
    val operationId: String,
    val commandSeq: Int,
    val memoryId: String? = null,
    val sourceMessageIds: List<String> = emptyList(),
    val denyField: String? = null,
)

data class RetractMemoryCommand(
    val operationId: String,
    val commandSeq: Int,
    val memoryId: String,
    val expectedVersion: Int,
)

data class RetractTierCommand(
    val operationId: String,
    val commandSeq: Int,
    val tier: String,
)

internal fun commandDigestPayload(command: Any): String {
    return when (command) {
        is SaveMemoryCommand -> listOf(
            command.tier,
            command.predicate,
            command.subjectId,
            command.viewpointId.orEmpty(),
            command.text,
            command.value,
            command.epistemic,
            command.memoryId.orEmpty(),
            command.expectedVersion?.toString().orEmpty(),
            command.eventId.orEmpty(),
            command.pinned.toString(),
            command.commitmentState.orEmpty(),
            command.coreAuthorized.toString(),
            command.personaEditor.toString(),
            command.relearn.toString(),
            command.allowConflictResolution.toString(),
            command.temporalJson,
            command.evidence.joinToString(",") {
                "${it.messageId}|${it.sourceRevision}|${it.quote}"
            },
        ).joinToString("\u0000")
        is PinMemoryCommand -> listOf(
            command.memoryId,
            command.expectedVersion,
            command.pinned,
        ).joinToString("\u0000")
        is ForgetMemoryCommand -> listOf(
            command.memoryId.orEmpty(),
            command.sourceMessageIds.sorted().joinToString(","),
            command.denyField.orEmpty(),
        ).joinToString("\u0000")
        is RetractMemoryCommand -> listOf(
            command.memoryId,
            command.expectedVersion,
        ).joinToString("\u0000")
        is RetractTierCommand -> command.tier
        else -> error("unsupported command")
    }
}

data class MemoryCommandResult(
    val status: String,
    val operationId: String,
    val memoryId: String? = null,
    val version: Int? = null,
    val deletionEpoch: Int? = null,
    val removedMemoryIds: List<String> = emptyList(),
    val errorCode: String? = null,
    val transaction: String = "committed",
)

