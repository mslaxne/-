package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context

class MemoryV2ExpiryService(
    context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
) {
    private val config = MemoryV2ConfigLoader.load(context)

    fun reconcileRecentEvents(): Int {
        val ttlSeconds = config.tiers["A2"]?.ttlSeconds ?: return 0
        val db = database.writableDatabase
        val updates = mutableListOf<Triple<String, String, Long>>()
        db.query(
            "memories",
            arrayOf(
                "scope_id",
                "memory_id",
                "temporal_json",
                "commitment_state",
                "source_anchor",
                "expires_at",
            ),
            "tier='A2' AND lifecycle='active' AND retracted_at IS NULL " +
                "AND expires_at IS NOT NULL",
            null,
            null,
            null,
            "expires_at,memory_id",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val currentExpiry = cursor.getLong(5)
                val semanticExpiry = MemoryV2TemporalPolicy.expiryFor(
                    reason = "recent_event",
                    temporalJson = cursor.getString(2).orEmpty(),
                    commitmentState = cursor.getString(3),
                    sourceAnchorEpochSeconds = cursor.getLong(4),
                    ttlSeconds = ttlSeconds,
                    fallbackTimezone = config.timezone,
                )
                if (semanticExpiry < currentExpiry) {
                    updates += Triple(
                        cursor.getString(0),
                        cursor.getString(1),
                        semanticExpiry,
                    )
                }
            }
        }
        if (updates.isEmpty()) {
            return 0
        }
        db.beginTransaction()
        try {
            updates.forEach { (scopeId, memoryId, expiresAt) ->
                db.update(
                    "memories",
                    ContentValues().apply { put("expires_at", expiresAt) },
                    "scope_id=? AND memory_id=? AND tier='A2'",
                    arrayOf(scopeId, memoryId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updates.size
    }
}
