package com.local.wxbotprobe.memory.v2

import android.annotation.SuppressLint
import android.content.Context
import java.util.UUID

enum class MemoryV2ReadPath {
    LEGACY,
    V2,
}

data class MemoryV2ReadPathSnapshot(
    val path: MemoryV2ReadPath,
    val enabled: Boolean,
    val activeRunId: String?,
)

data class MemoryV2ActivationEvidence(
    val runState: String?,
    val importedItems: Int,
    val missingTargets: Int,
    val scopeBarriers: Int,
)

object MemoryV2ActivationPolicy {
    fun canActivate(evidence: MemoryV2ActivationEvidence): Boolean {
        return evidence.runState == "committed" &&
            evidence.importedItems > 0 &&
            evidence.missingTargets == 0 &&
            evidence.scopeBarriers == 0
    }
}

data class MemoryV2ActivationResult(
    val status: String,
    val snapshot: MemoryV2ReadPathSnapshot,
    val errorCode: String? = null,
)

@SuppressLint("ApplySharedPref")
class MemoryV2ContactIdentityStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun resolve(
        identityKey: String,
        knownContactId: String? = null,
    ): String {
        require(identityKey.isNotBlank()) { "identityKey is required" }
        if (!knownContactId.isNullOrBlank()) {
            bind(identityKey, knownContactId)
            return knownContactId
        }
        val key = key(identityKey)
        preferences.getString(key, null)?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        val contactId = UUID.randomUUID().toString()
        preferences.edit().putString(key, contactId).commit()
        return contactId
    }

    @Synchronized
    fun bind(identityKey: String, contactId: String) {
        require(identityKey.isNotBlank()) { "identityKey is required" }
        require(contactId.isNotBlank()) { "contactId is required" }
        preferences.edit().putString(key(identityKey), contactId).commit()
    }

    private fun key(identityKey: String): String {
        return "contact:" + MemoryV2Crypto.sha256(identityKey)
    }

    companion object {
        private const val PREFS = "memory_v2_contacts"
    }
}

class MemoryV2ReadPathService(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
) {
    fun snapshot(): MemoryV2ReadPathSnapshot {
        return MemoryV2Settings.readPathSnapshot(context)
    }

    fun activate(runId: String): MemoryV2ActivationResult {
        require(runId.isNotBlank()) { "runId is required" }
        val evidence = activationEvidence(runId)
        if (!MemoryV2ActivationPolicy.canActivate(evidence)) {
            return MemoryV2ActivationResult(
                status = "blocked",
                snapshot = snapshot(),
                errorCode = activationError(evidence).name,
            )
        }
        val committed = MemoryV2Settings.activateForRead(context, runId)
        return MemoryV2ActivationResult(
            status = if (committed) "activated" else "failed",
            snapshot = snapshot(),
            errorCode = if (committed) {
                null
            } else {
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name
            },
        )
    }

    fun rollback(): MemoryV2ActivationResult {
        val committed = MemoryV2Settings.rollbackReadPath(context)
        return MemoryV2ActivationResult(
            status = if (committed) "rolled_back" else "failed",
            snapshot = snapshot(),
            errorCode = if (committed) {
                null
            } else {
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name
            },
        )
    }

    private fun activationEvidence(runId: String): MemoryV2ActivationEvidence {
        val db = database.readableDatabase
        val runState = db.rawQuery(
            "SELECT state FROM migration_runs WHERE run_id=?",
            arrayOf(runId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val importedItems = count(
            db,
            "SELECT COUNT(*) FROM migration_items " +
                "WHERE run_id=? AND state='imported'",
            arrayOf(runId),
        )
        val missingTargets = count(
            db,
            "SELECT COUNT(*) FROM migration_items i " +
                "LEFT JOIN memories m ON m.scope_id=i.scope_id " +
                "AND m.memory_id=i.target_id " +
                "WHERE i.run_id=? AND i.state='imported' AND m.memory_id IS NULL",
            arrayOf(runId),
        )
        val scopeBarriers = count(
            db,
            "SELECT COUNT(*) FROM migration_items i " +
                "JOIN scopes s ON s.scope_id=i.scope_id " +
                "WHERE i.run_id=? AND i.state='imported' AND " +
                "(s.state<>'active' OR s.deletion_epoch>0)",
            arrayOf(runId),
        )
        return MemoryV2ActivationEvidence(
            runState = runState,
            importedItems = importedItems,
            missingTargets = missingTargets,
            scopeBarriers = scopeBarriers,
        )
    }

    private fun activationError(
        evidence: MemoryV2ActivationEvidence,
    ): MemoryV2ErrorCode {
        if (evidence.scopeBarriers > 0) {
            return MemoryV2ErrorCode.LEGACY_DELETION_BARRIER
        }
        if (evidence.runState != "committed" || evidence.importedItems == 0) {
            return MemoryV2ErrorCode.LEGACY_MAPPING_NOT_APPROVED
        }
        return MemoryV2ErrorCode.STORAGE_UNAVAILABLE
    }

    private fun count(
        db: android.database.sqlite.SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): Int {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
}
