package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.bot.PersonaBindingStore
import com.local.wxbotprobe.bot.PersonaPresetRepository
import com.local.wxbotprobe.bot.PersonaSelectionService
import com.local.wxbotprobe.bot.RoleplayConsentStore
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2IdentityStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class DebugPersonaPresetSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        android.util.Log.i("DebugPersonaPreset", "snapshot request received")
        val pending = goAsync()
        Thread {
            val output = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "diagnostics/persona-preset-snapshot.json",
            )
            try {
                val result = runCatching {
                    val migrate = intent?.getBooleanExtra("migrate", false) == true
                    snapshot(context.applicationContext, migrate)
                }.getOrElse {
                    JSONObject()
                        .put("status", "failed")
                        .put("error", it.stackTraceToString())
                }
                output.parentFile?.mkdirs()
                val temporary = File(output.parentFile, "${output.name}.tmp")
                temporary.writeText(result.toString(2), Charsets.UTF_8)
                Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                android.util.Log.i(
                    "DebugPersonaPreset",
                    result.toString(),
                )
            } catch (error: Throwable) {
                android.util.Log.e(
                    "DebugPersonaPreset",
                    "snapshot write failed",
                    error,
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun snapshot(context: Context, migrate: Boolean): JSONObject {
        val repository = PersonaPresetRepository.forContext(context)
        val migrated = if (migrate) repository.ensureMigrated() else null
        val database = MemoryV2Database(context)
        try {
            val db = database.readableDatabase
            val scopes = JSONArray()
            db.rawQuery(
                "SELECT scope_id,contact_id,persona_id,persona_revision " +
                    "FROM scopes ORDER BY contact_id,persona_id,scope_id",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    scopes.put(
                        JSONObject()
                            .put("scope_id", cursor.getString(0))
                            .put("contact_id", cursor.getString(1))
                            .put("persona_id", cursor.getString(2))
                            .put("persona_revision", cursor.getInt(3)),
                    )
                }
            }
            val scopeMetrics = JSONArray()
            if (repository.repositoryExists()) {
                for (index in 0 until scopes.length()) {
                    val scope = scopes.getJSONObject(index)
                    val scopeId = scope.getString("scope_id")
                    val contactId = scope.getString("contact_id")
                    scopeMetrics.put(
                        JSONObject()
                            .put("scope_id", scopeId)
                            .put("contact_id", contactId)
                            .put("persona_id", scope.getString("persona_id"))
                            .put(
                                "selected_persona_id",
                                PersonaSelectionService(context)
                                    .selectedForContact(contactId)
                                    .personaId,
                            )
                            .put("messages", count(
                                db,
                                "SELECT COUNT(*) FROM messages WHERE scope_id=?",
                                arrayOf(scopeId),
                            ))
                            .put("sessions", count(
                                db,
                                "SELECT COUNT(*) FROM sessions WHERE scope_id=?",
                                arrayOf(scopeId),
                            ))
                            .put("memories", count(
                                db,
                                "SELECT COUNT(*) FROM memories WHERE scope_id=?",
                                arrayOf(scopeId),
                            ))
                            .put("evidence", count(
                                db,
                                "SELECT COUNT(*) FROM evidence WHERE scope_id=?",
                                arrayOf(scopeId),
                            ))
                            .put("jobs", count(
                                db,
                                "SELECT COUNT(*) FROM jobs WHERE scope_id=?",
                                arrayOf(scopeId),
                            ))
                            .put("conflicts", count(
                                db,
                                "SELECT COUNT(*) FROM memory_conflicts WHERE scope_id=?",
                                arrayOf(scopeId),
                            ))
                            .put("relations", count(
                                db,
                                "SELECT COUNT(*) FROM memory_relations WHERE scope_id=?",
                                arrayOf(scopeId),
                            ))
                            .put("outbox", count(
                                db,
                                "SELECT COUNT(*) FROM outbox WHERE scope_id=?",
                                arrayOf(scopeId),
                            )),
                    )
                }
            }
            val bindings = JSONArray()
            PersonaBindingStore(context).bindings()
                .toSortedMap()
                .forEach { (contactId, personaId) ->
                    bindings.put(
                        JSONObject()
                            .put("contact_id", contactId)
                            .put("persona_id", personaId),
                    )
                }
            val roleplayConsents = JSONArray()
            RoleplayConsentStore(context).records().forEach { consent ->
                roleplayConsents.put(
                    JSONObject()
                        .put("contact_id", consent.contactId)
                        .put("persona_id", consent.personaId)
                        .put("persona_revision", consent.personaRevision)
                        .put("confirmed_at", consent.confirmedAt)
                        .put(
                            "revoked_at",
                            consent.revokedAt ?: JSONObject.NULL,
                        ),
                )
            }
            val now = System.currentTimeMillis() / 1_000L
            val tiers = JSONObject()
            listOf("A0", "A1", "A2", "A3").forEach { tier ->
                tiers.put(
                    tier,
                    count(
                        db,
                        "SELECT COUNT(*) FROM memories WHERE tier=? " +
                            "AND lifecycle='active' AND retracted_at IS NULL " +
                            "AND (expires_at IS NULL OR expires_at>?)",
                        arrayOf(tier, now.toString()),
                    ),
                )
            }
            val presets = JSONArray()
            if (repository.repositoryExists()) {
                repository.list(includeArchived = true).forEach { preset ->
                    presets.put(
                        JSONObject()
                            .put("id", preset.id)
                            .put("name", preset.name)
                            .put("is_default", preset.isDefault)
                            .put("archived", preset.archived)
                            .put("revision", preset.revision),
                    )
                }
            }
            val legacy = File(
                context.filesDir,
                "persona/${PersonaPresetRepository.LEGACY_FILE_NAME}",
            )
            val repositoryFile = File(
                context.filesDir,
                "persona/${PersonaPresetRepository.REPOSITORY_FILE_NAME}",
            )
            return JSONObject()
                .put("status", "passed")
                .put(
                    "schema_version",
                    count(db, "PRAGMA user_version", emptyArray()),
                )
                .put("migration_requested", migrate)
                .put(
                    "migration_legacy_file",
                    migrated?.migratedLegacyFile ?: false,
                )
                .put(
                    "identity_persona_id",
                    MemoryV2IdentityStore(context).personaId,
                )
                .put("scope_count", scopes.length())
                .put("scopes", scopes)
                .put("scope_metrics", scopeMetrics)
                .put("bindings", bindings)
                .put("roleplay_consents", roleplayConsents)
                .put(
                    "message_count",
                    count(db, "SELECT COUNT(*) FROM messages", emptyArray()),
                )
                .put(
                    "session_count",
                    count(db, "SELECT COUNT(*) FROM sessions", emptyArray()),
                )
                .put(
                    "active_memory_count",
                    count(
                        db,
                        "SELECT COUNT(*) FROM memories WHERE lifecycle='active' " +
                            "AND retracted_at IS NULL " +
                            "AND (expires_at IS NULL OR expires_at>?)",
                        arrayOf(now.toString()),
                    ),
                )
                .put("active_memory_tiers", tiers)
                .put(
                    "job_count",
                    count(db, "SELECT COUNT(*) FROM jobs", emptyArray()),
                )
                .put(
                    "conflict_count",
                    count(
                        db,
                        "SELECT COUNT(*) FROM memory_conflicts",
                        emptyArray(),
                    ),
                )
                .put(
                    "relation_count",
                    count(
                        db,
                        "SELECT COUNT(*) FROM memory_relations",
                        emptyArray(),
                    ),
                )
                .put(
                    "outbox_count",
                    count(db, "SELECT COUNT(*) FROM outbox", emptyArray()),
                )
                .put(
                    "persona_migration_audit_count",
                    count(
                        db,
                        "SELECT COUNT(*) FROM persona_migration_audit",
                        emptyArray(),
                    ),
                )
                .put("legacy_profile_exists", legacy.isFile)
                .put("legacy_profile_bytes", legacy.takeIf { it.isFile }?.length() ?: 0)
                .put(
                    "legacy_profile_sha256",
                    legacy.takeIf { it.isFile }?.let(::sha256).orEmpty(),
                )
                .put("preset_repository_exists", repositoryFile.isFile)
                .put(
                    "preset_repository_bytes",
                    repositoryFile.takeIf { it.isFile }?.length() ?: 0,
                )
                .put(
                    "preset_repository_sha256",
                    repositoryFile.takeIf { it.isFile }?.let(::sha256).orEmpty(),
                )
                .put("presets", presets)
        } finally {
            database.close()
        }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
