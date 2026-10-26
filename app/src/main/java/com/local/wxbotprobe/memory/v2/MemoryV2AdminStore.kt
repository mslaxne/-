package com.local.wxbotprobe.memory.v2

import android.annotation.SuppressLint
import android.content.Context
import com.local.wxbotprobe.bot.PersonaBindingStore
import com.local.wxbotprobe.bot.PersonaSelectionService
import com.local.wxbotprobe.bot.RoleplayConsentStore
import com.local.wxbotprobe.chat.TextRules

data class MemoryV2ContactOverview(
    val scopeId: String,
    val contactId: String,
    val displayName: String,
    val identityKey: String,
    val sessionCount: Int,
    val messageCount: Int,
    val extractedMessageCount: Int,
    val pendingExtractionCount: Int,
    val extractionIssueCount: Int,
    val summaryDoneCount: Int,
    val summaryGapCount: Int,
    val pendingSummaryCount: Int,
    val memoryCount: Int,
    val pendingCommitments: Int,
    val pendingConflicts: Int,
)

data class MemoryV2Overview(
    val contacts: List<MemoryV2ContactOverview>,
    val sessionCount: Int,
    val messageCount: Int,
    val extractedMessageCount: Int,
    val pendingExtractionCount: Int,
    val extractionIssueCount: Int,
    val summaryDoneCount: Int,
    val summaryGapCount: Int,
    val pendingSummaryCount: Int,
    val memoryCount: Int,
    val pendingCommitments: Int,
    val pendingConflicts: Int,
)

data class MemoryV2MemoryRow(
    val memoryId: String,
    val version: Int,
    val text: String,
    val tier: String,
    val predicate: String,
    val subjectId: String,
    val viewpointId: String?,
    val epistemic: String,
    val commitmentState: String,
    val temporalJson: String,
    val updatedAt: Long,
    val expiresAt: Long?,
)

data class MemoryV2PersonaReferenceUsage(
    val contactIds: Set<String> = emptySet(),
    val scopeCount: Int = 0,
    val memoryCount: Int = 0,
)

@SuppressLint("ApplySharedPref")
class MemoryV2AdminStore(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val personaIdForContact: (String) -> String = { contactId ->
        PersonaSelectionService(context)
            .selectedForContact(contactId)
            .personaId
    },
) {
    private val personaBindings = PersonaBindingStore(context)

    fun overview(): MemoryV2Overview {
        val contacts = contacts()
        return MemoryV2Overview(
            contacts = contacts,
            sessionCount = contacts.sumOf { it.sessionCount },
            messageCount = contacts.sumOf { it.messageCount },
            extractedMessageCount = contacts.sumOf { it.extractedMessageCount },
            pendingExtractionCount = contacts.sumOf { it.pendingExtractionCount },
            extractionIssueCount = contacts.sumOf { it.extractionIssueCount },
            summaryDoneCount = contacts.sumOf { it.summaryDoneCount },
            summaryGapCount = contacts.sumOf { it.summaryGapCount },
            pendingSummaryCount = contacts.sumOf { it.pendingSummaryCount },
            memoryCount = contacts.sumOf { it.memoryCount },
            pendingCommitments = contacts.sumOf { it.pendingCommitments },
            pendingConflicts = contacts.sumOf { it.pendingConflicts },
        )
    }

    fun contacts(): List<MemoryV2ContactOverview> {
        val now = System.currentTimeMillis() / 1_000L
        return database.readableDatabase.rawQuery(
            "SELECT s.scope_id,s.contact_id,s.persona_id," +
                "COALESCE((" +
                "SELECT e.display_name FROM entities e " +
                "WHERE e.scope_id=s.scope_id AND e.kind='user' LIMIT 1" +
                "),s.contact_id) AS display_name," +
                "(SELECT COUNT(*) FROM sessions x WHERE x.scope_id=s.scope_id) " +
                "AS session_count," +
                "(SELECT COUNT(*) FROM messages m WHERE m.scope_id=s.scope_id " +
                "AND m.state='live') AS message_count," +
                "((SELECT COUNT(*) FROM messages m WHERE m.scope_id=s.scope_id " +
                "AND m.extraction_state IN ('done','skipped')) + " +
                "(SELECT COUNT(*) FROM message_cleanup c " +
                "WHERE c.scope_id=s.scope_id AND c.state='purged')) " +
                "AS extracted_count," +
                "(SELECT COUNT(*) FROM messages m WHERE m.scope_id=s.scope_id " +
                "AND m.extraction_state='pending' AND m.state!='forgotten') " +
                "AS pending_extraction_count," +
                "(SELECT COUNT(*) FROM messages m WHERE m.scope_id=s.scope_id " +
                "AND m.extraction_state='gap') AS extraction_issue_count," +
                "((SELECT COUNT(*) FROM messages m WHERE m.scope_id=s.scope_id " +
                "AND m.summary_state='done') + " +
                "(SELECT COUNT(*) FROM message_cleanup c " +
                "WHERE c.scope_id=s.scope_id AND c.state='purged')) " +
                "AS summary_done_count," +
                "(SELECT COUNT(*) FROM messages m WHERE m.scope_id=s.scope_id " +
                "AND m.summary_state='gap') AS summary_gap_count," +
                "(SELECT COUNT(*) FROM messages m WHERE m.scope_id=s.scope_id " +
                "AND m.summary_state='pending' AND m.state!='forgotten') " +
                "AS pending_summary_count," +
                "(SELECT COUNT(*) FROM memories m WHERE m.scope_id=s.scope_id " +
                "AND m.lifecycle='active' AND m.retracted_at IS NULL AND " +
                "(m.expires_at IS NULL OR m.expires_at>?)) AS memory_count," +
                "(SELECT COUNT(*) FROM memories m WHERE m.scope_id=s.scope_id " +
                "AND m.lifecycle='active' AND m.retracted_at IS NULL AND " +
                "(m.expires_at IS NULL OR m.expires_at>?) AND " +
                "m.commitment_state IN " +
                "('proposed','active','overdue_unknown')) AS pending_count " +
                ",(SELECT COUNT(*) FROM memory_conflicts c " +
                "WHERE c.scope_id=s.scope_id AND c.state='pending') " +
                "AS pending_conflict_count " +
                "FROM scopes s WHERE s.state='active' " +
                "ORDER BY display_name COLLATE NOCASE,scope_id",
            arrayOf(now.toString(), now.toString()),
        ).use { cursor ->
            buildList {
                val emittedContacts = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(1).orEmpty()
                    val personaId = cursor.getString(2).orEmpty()
                    if (contactId.isBlank() || personaId.isBlank()) {
                        continue
                    }
                    val selectedPersonaId = personaIdForContact(contactId)
                    if (
                        personaId != selectedPersonaId ||
                        !emittedContacts.add(contactId)
                    ) {
                        continue
                    }
                    val displayName = cursor.getString(3).orEmpty()
                    add(
                        MemoryV2ContactOverview(
                            scopeId = cursor.getString(0),
                            contactId = contactId,
                            displayName = displayName,
                            identityKey = TextRules.normalise(displayName),
                            sessionCount = cursor.getInt(4),
                            messageCount = cursor.getInt(5),
                            extractedMessageCount = cursor.getInt(6),
                            pendingExtractionCount = cursor.getInt(7),
                            extractionIssueCount = cursor.getInt(8),
                            summaryDoneCount = cursor.getInt(9),
                            summaryGapCount = cursor.getInt(10),
                            pendingSummaryCount = cursor.getInt(11),
                            memoryCount = cursor.getInt(12),
                            pendingCommitments = cursor.getInt(13),
                            pendingConflicts = cursor.getInt(14),
                        ),
                    )
                }
            }
        }
    }

    fun personaUsages(): Map<String, MemoryV2PersonaReferenceUsage> {
        val usage = mutableMapOf<String, MutablePersonaUsage>()
        val scopedContactIds = mutableSetOf<String>()
        database.readableDatabase.rawQuery(
            "SELECT persona_id,contact_id,COUNT(*) FROM scopes " +
                "GROUP BY persona_id,contact_id",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val personaId = cursor.getString(0).orEmpty()
                if (personaId.isBlank()) {
                    continue
                }
                val entry = usage.getOrPut(personaId) {
                    MutablePersonaUsage()
                }
                cursor.getString(1)?.takeIf { it.isNotBlank() }?.let {
                    scopedContactIds += it
                }
                entry.scopeCount += cursor.getInt(2)
            }
        }
        val now = System.currentTimeMillis() / 1_000L
        database.readableDatabase.rawQuery(
            "SELECT s.persona_id,SUM(CASE WHEN m.lifecycle='active' " +
                "AND m.retracted_at IS NULL " +
                "AND (m.expires_at IS NULL OR m.expires_at>?) " +
                "THEN 1 ELSE 0 END) FROM scopes s " +
                "LEFT JOIN memories m ON m.scope_id=s.scope_id " +
                "WHERE s.persona_id<>'' GROUP BY s.persona_id",
            arrayOf(now.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val personaId = cursor.getString(0).orEmpty()
                val entry = usage.getOrPut(personaId) {
                    MutablePersonaUsage()
                }
                entry.memoryCount = cursor.getInt(1)
            }
        }
        val referencedContactIds = scopedContactIds +
            personaBindings.bindings().keys
        referencedContactIds.forEach { contactId ->
            val selectedPersonaId = personaIdForContact(contactId)
            usage.getOrPut(selectedPersonaId) { MutablePersonaUsage() }
                .contactIds += contactId
        }
        return usage.mapValues { (_, value) ->
            MemoryV2PersonaReferenceUsage(
                contactIds = value.contactIds,
                scopeCount = value.scopeCount,
                memoryCount = value.memoryCount,
            )
        }
    }

    fun personaUsage(personaId: String): MemoryV2PersonaReferenceUsage {
        if (personaId.isBlank()) {
            return MemoryV2PersonaReferenceUsage()
        }
        return personaUsages()[personaId]
            ?: MemoryV2PersonaReferenceUsage()
    }

    fun deleteScope(scopeId: String): Boolean {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val deleted = db.delete(
                "scopes",
                "scope_id=?",
                arrayOf(scopeId),
            ) == 1
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
        }
    }

    fun memories(
        scopeId: String,
        limit: Int = 50,
    ): List<MemoryV2MemoryRow> {
        val now = System.currentTimeMillis() / 1_000L
        return database.readableDatabase.rawQuery(
            "SELECT memory_id,version,text,tier,predicate,subject_id," +
                "viewpoint_id,epistemic,COALESCE(commitment_state,'')," +
                "temporal_json,updated_at,expires_at " +
                "FROM memories WHERE scope_id=? AND lifecycle='active' " +
                "AND retracted_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at>?) " +
                "ORDER BY pinned DESC,updated_at DESC LIMIT ?",
            arrayOf(
                scopeId,
                now.toString(),
                limit.coerceIn(1, 200).toString(),
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MemoryV2MemoryRow(
                            memoryId = cursor.getString(0).orEmpty(),
                            version = cursor.getInt(1),
                            text = MemoryV2MemoryWording.forCurrentPersona(
                                cursor.getString(2).orEmpty(),
                            ),
                            tier = cursor.getString(3).orEmpty(),
                            predicate = cursor.getString(4).orEmpty(),
                            subjectId = cursor.getString(5).orEmpty(),
                            viewpointId = if (cursor.isNull(6)) {
                                null
                            } else {
                                cursor.getString(6)
                            },
                            epistemic = cursor.getString(7).orEmpty(),
                            commitmentState = cursor.getString(8).orEmpty(),
                            temporalJson = cursor.getString(9).orEmpty(),
                            updatedAt = cursor.getLong(10),
                            expiresAt = if (cursor.isNull(11)) {
                                null
                            } else {
                                cursor.getLong(11)
                            },
                        ),
                    )
                }
            }
        }
    }

    fun scope(scopeId: String): TrustedScopeContext {
        return database.readableDatabase.query(
            "scopes",
            arrayOf(
                "owner_id",
                "contact_id",
                "agent_id",
                "persona_id",
                "realm_id",
                "persona_revision",
                "revision",
                "deletion_epoch",
                "import_generation",
                "retention_policy",
                "state",
                "restore_guard_id",
            ),
            "scope_id=?",
            arrayOf(scopeId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            return TrustedScopeContext(
                scopeId = scopeId,
                key = MemoryScopeKey(
                    ownerId = cursor.getString(0).orEmpty(),
                    contactId = cursor.getString(1).orEmpty(),
                    agentId = cursor.getString(2).orEmpty(),
                    personaId = cursor.getString(3).orEmpty(),
                    realmId = cursor.getString(4).orEmpty(),
                ),
                personaRevision = cursor.getInt(5),
                revision = cursor.getInt(6),
                deletionEpoch = cursor.getInt(7),
                importGeneration = cursor.getInt(8),
                retentionPolicy = cursor.getString(9).orEmpty(),
                state = cursor.getString(10).orEmpty(),
                restoreGuardId = cursor.getString(11).orEmpty(),
            )
        }
    }

    fun conflicts(scopeId: String): List<MemoryV2ConflictRow> {
        return MemoryV2ConflictService(context, database).list(scopeId)
    }

    fun resolveConflict(
        scopeId: String,
        conflictId: String,
        decision: String,
        a0Confirmed: Boolean = false,
    ): MemoryV2ConflictResolution {
        return MemoryV2ConflictService(context, database).resolve(
            scopeId,
            conflictId,
            decision,
            a0Confirmed,
        )
    }

    fun clearAll() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("migration_runs", null, null)
            db.delete("persona_migration_audit", null, null)
            db.delete("scopes", null, null)
            db.delete("capacity_ledger", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        context.getSharedPreferences(
            CONTACT_IDENTITIES,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        context.getSharedPreferences(
            MEMORY_SETTINGS,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        context.getSharedPreferences(
            CALL_BUDGET,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        context.getSharedPreferences(
            MEMORY_CLEANUP_SETTINGS,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        personaBindings.clearAll()
        RoleplayConsentStore(context).clearAll()
    }

    companion object {
        private const val CONTACT_IDENTITIES = "memory_v2_contacts"
        private const val MEMORY_SETTINGS = "memory_v2_settings"
        private const val CALL_BUDGET = "memory_v2_call_budget"
        private const val MEMORY_CLEANUP_SETTINGS = "memory_cleanup_settings"
    }

    private data class MutablePersonaUsage(
        val contactIds: MutableSet<String> = mutableSetOf(),
        var scopeCount: Int = 0,
        var memoryCount: Int = 0,
    )
}
