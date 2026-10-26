package com.local.wxbotprobe.memory.v2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MemoryV2Database(
    private val context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(
    context,
    databaseName,
    null,
    DATABASE_VERSION,
) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA journal_mode = WAL", null).use { cursor ->
            cursor.moveToFirst()
        }
        db.rawQuery("PRAGMA synchronous = FULL", null).use { cursor ->
            cursor.moveToFirst()
        }
        db.rawQuery("PRAGMA busy_timeout = 250", null).use { cursor ->
            cursor.moveToFirst()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        schemaStatements().forEach(db::execSQL)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS message_cleanup (" +
                    "scope_id TEXT NOT NULL,message_id TEXT NOT NULL," +
                    "session_id TEXT NOT NULL,client_message_id TEXT NOT NULL," +
                    "seq INTEGER NOT NULL,completed_at INTEGER NOT NULL," +
                    "body_due_at INTEGER NOT NULL,metadata_due_at INTEGER NOT NULL," +
                    "body_redacted_at INTEGER,state TEXT NOT NULL DEFAULT 'pending' " +
                    "CHECK(state IN ('pending','redacted','purged','blocked'))," +
                    "last_error TEXT,created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "PRIMARY KEY(scope_id,message_id)," +
                    "UNIQUE(scope_id,session_id,client_message_id)," +
                    "FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) " +
                    "ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS message_cleanup_due " +
                    "ON message_cleanup(state,body_due_at,metadata_due_at)",
            )
        }
        if (oldVersion < 3) {
            db.execSQL(
                "ALTER TABLE memories ADD COLUMN retracted_at INTEGER",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS memory_conflicts (" +
                    "scope_id TEXT NOT NULL,conflict_id TEXT NOT NULL," +
                    "memory_id TEXT NOT NULL,target_version INTEGER NOT NULL " +
                    "CHECK(target_version>0),operation TEXT NOT NULL," +
                    "candidate_json TEXT NOT NULL,state TEXT NOT NULL " +
                    "DEFAULT 'pending' CHECK(state IN ('pending','resolved','dismissed'))," +
                    "resolution TEXT,resolved_at INTEGER," +
                    "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL," +
                    "PRIMARY KEY(scope_id,conflict_id)," +
                    "FOREIGN KEY(scope_id,memory_id) REFERENCES memories(scope_id,memory_id) " +
                    "ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS conflicts_pending " +
                    "ON memory_conflicts(scope_id,state,created_at)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS memory_relations (" +
                    "scope_id TEXT NOT NULL,relation_id TEXT NOT NULL," +
                    "source_memory_id TEXT NOT NULL,target_memory_id TEXT NOT NULL," +
                    "relation_type TEXT NOT NULL CHECK(relation_type IN " +
                    "('SUPPORTS','ELABORATES')),target_version INTEGER NOT NULL " +
                    "CHECK(target_version>0),created_at INTEGER NOT NULL," +
                    "PRIMARY KEY(scope_id,relation_id)," +
                    "UNIQUE(scope_id,source_memory_id,target_memory_id,relation_type)," +
                    "FOREIGN KEY(scope_id,source_memory_id) REFERENCES " +
                    "memories(scope_id,memory_id) ON DELETE CASCADE," +
                    "FOREIGN KEY(scope_id,target_memory_id) REFERENCES " +
                    "memories(scope_id,memory_id) ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS relations_target " +
                    "ON memory_relations(scope_id,target_memory_id,relation_type)",
            )
        }
        if (oldVersion < 4) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS persona_migration_audit (" +
                    "source_scope_id TEXT NOT NULL,target_scope_id TEXT NOT NULL," +
                    "contact_id TEXT NOT NULL,source_persona_id TEXT NOT NULL," +
                    "target_persona_id TEXT NOT NULL,source_memory_id TEXT NOT NULL," +
                    "target_memory_id TEXT NOT NULL,source_version INTEGER NOT NULL," +
                    "target_version INTEGER NOT NULL DEFAULT 1," +
                    "target_fact_key TEXT,state TEXT NOT NULL DEFAULT 'pending' " +
                    "CHECK(state IN ('pending','committed','failed','rolled_back'))," +
                    "relation_summary_json TEXT NOT NULL DEFAULT '{}'," +
                    "last_error TEXT,created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL,committed_at INTEGER," +
                    "rolled_back_at INTEGER," +
                    "PRIMARY KEY(source_scope_id,source_memory_id,target_scope_id)," +
                    "UNIQUE(target_scope_id,target_memory_id))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS persona_migration_audit_target " +
                    "ON persona_migration_audit(target_scope_id,state,updated_at)",
            )
        }
        if (newVersion > 4) {
            throw IllegalStateException(
                "Memory v2 migration required: $oldVersion -> $newVersion",
            )
        }
    }

    internal fun schemaStatements(): List<String> {
        val raw = context.assets.open("memory_v2/schema.sql")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return raw.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it.startsWith("PRAGMA ", ignoreCase = true) }
    }

    companion object {
        const val DATABASE_NAME = "memory-v2.db"
        const val DATABASE_VERSION = 4
    }
}
