package com.local.wxbotprobe.memory.v2

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2AssetsTest {
    @Test
    fun `vendored schema and contract match framework v2`() {
        assertEquals(
            "9cac52a6288f437358d05b1f855878c8d44ea6d0a6605a4b7e8598afcfdc10ab",
            sha256(asset("schema.sql")),
        )
        assertEquals(
            "9add4e3079b11e5814e962c93df8a41b1270208b9b01364116c4b0eab0f72dd7",
            sha256(asset("memory_contracts.schema.json")),
        )
    }

    @Test
    fun `schema contains all memory v2 tables and foreign key configuration`() {
        val schema = asset("schema.sql").readText()
        assertEquals(23, Regex("(?m)^CREATE TABLE").findAll(schema).count())
        assertTrue("PRAGMA foreign_keys = ON" in schema)
        listOf(
            "scopes",
            "entities",
            "aliases",
            "sessions",
            "messages",
            "message_cleanup",
            "memories",
            "evidence",
            "dependencies",
            "memory_history",
            "memory_conflicts",
            "memory_relations",
            "jobs",
            "coverage",
            "outbox",
            "tombstones",
            "capacity_ledger",
            "audit_events",
            "scheduled_tasks",
            "operations",
            "migration_runs",
            "migration_items",
            "persona_migration_audit",
        ).forEach { table ->
            assertTrue("missing $table", "CREATE TABLE IF NOT EXISTS $table" in schema)
        }
        assertTrue(schema.contains("retracted_at INTEGER"))
        assertTrue(schema.contains("resolution TEXT, resolved_at INTEGER"))
    }

    @Test
    fun `runtime config starts disabled and keeps resource bounds`() {
        val config = JSONObject(asset("memory_config.json").readText())

        assertFalse(config.getBoolean("enabled"))
        assertEquals("2.0.0", config.getString("schema_version"))
        assertEquals(
            "extract-2.5.1",
            config.getJSONObject("versions").getString("extraction_prompt"),
        )
        assertEquals(
            "summary-2.2.2",
            config.getJSONObject("versions").getString("summary_prompt"),
        )
        assertEquals(
            8,
            config.getJSONObject("context")
                .getInt("reply_context_recent_raw_messages"),
        )
        assertEquals(
            16,
            config.getJSONObject("background")
                .getInt("recent_context_sources"),
        )
        assertEquals(
            8_192,
            config.getJSONObject("context")
                .getInt("working_summary_max_utf8_bytes"),
        )
        assertTrue(
            config.getJSONObject("context")
                .getJSONObject("reply_context_provider_window_tokens")
                .getInt("openrouter_free") > 0,
        )
        assertEquals(
            config.getJSONObject("tiers").getJSONObject("A1").getLong("max_bytes") * 3,
            config.getJSONObject("tiers").getJSONObject("A2").getLong("max_bytes"),
        )
        assertEquals(
            0,
            config.getJSONObject("context").getInt("target_input_tokens"),
        )
        assertEquals(
            1.0,
            config.getJSONObject("context").getDouble("fallback_input_fraction"),
            0.0,
        )
        assertEquals(
            0,
            config.getJSONObject("background").getInt("max_sources"),
        )
        assertEquals(
            0,
            config.getJSONObject("retrieval").getInt("candidate_limit"),
        )
        assertEquals(
            0,
            config.getJSONObject("retrieval").getInt("selected_limit"),
        )
        assertEquals(
            0,
            config.getJSONObject("retrieval").getInt("reply_candidate_limit"),
        )
        assertEquals(
            0,
            config.getJSONObject("retrieval").getInt("reply_selected_limit"),
        )
        assertEquals(
            3,
            config.getJSONObject("retrieval").getInt("reply_context_exact_days"),
        )
        assertEquals(
            7,
            config.getJSONObject("retrieval").getInt("reply_context_expanded_days"),
        )
        assertEquals(
            0.65,
            config.getJSONObject("retrieval").getDouble("reply_context_similarity"),
            0.0,
        )
        val resources = config.getJSONObject("resources")
        listOf(
            "messages",
            "jobs",
            "outbox",
            "tombstones",
            "operations",
            "memory_conflicts",
            "memory_relations",
        ).forEach {
            assertTrue(resources.has(it))
            assertTrue(resources.getJSONObject(it).getInt("max_records") > 0)
        }
    }

    @Test
    fun `relationship role is available for human-confirmed A0 memory`() {
        val predicates = JSONObject(asset("predicates.json").readText())
            .getJSONObject("predicates")
        val role = predicates.getJSONObject("relationship.role")
        assertTrue(role.getBoolean("allow_A0"))
        assertEquals("single", role.getString("cardinality"))
    }

    @Test
    fun `configured model profile uses the provider window without an application cap`() {
        val profiles = JSONObject(asset("model_profiles.json").readText())
            .getJSONArray("profiles")
        val configured = (0 until profiles.length())
            .map { profiles.getJSONObject(it) }
            .first { it.getString("profile_id") == "deepseek-flash-text-floor-2026-10-23" }

        assertEquals(1_048_576, configured.getInt("context_window_tokens"))
        assertEquals("provider_documented_window", configured.getString("window_kind"))
    }

    @Test
    fun `prompts encode confirmation and rolling A3 policy`() {
        val answer = asset("answer.txt").readText()
        val extract = asset("extract.txt").readText()
        val summary = asset("summary.txt").readText()

        assertTrue("先" in answer && "确认" in answer)
        assertTrue("A3" in answer && "不要" in answer)
        assertTrue("CONTRADICT" in extract && "待确认" in extract)
        assertTrue("A3" in extract)
        assertTrue("working_summary_context" in extract)
        assertTrue("recent_source_context" in extract)
        assertTrue("不能作为 evidence" in extract)
        assertTrue("phase.closed=false" in extract)
        assertTrue("连续前缀" in extract)
        assertTrue("滚动检查点" in summary && "8 KiB" in summary)
    }

    private fun asset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/memory_v2/$name"),
            File("app/src/main/assets/memory_v2/$name"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("asset not found: $name")
    }

    private fun sha256(file: File): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }
}
