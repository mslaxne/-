package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.memory.v2.AppendMessageRequest
import com.local.wxbotprobe.memory.v2.MemoryScopeKey
import com.local.wxbotprobe.memory.v2.MemoryV2BatchCommitService
import com.local.wxbotprobe.memory.v2.MemoryV2CallBudgetStore
import com.local.wxbotprobe.memory.v2.MemoryV2ConfigLoader
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2DeepSeekBatchGenerator
import com.local.wxbotprobe.memory.v2.MemoryV2ErrorCode
import com.local.wxbotprobe.memory.v2.MemoryV2Exception
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import com.local.wxbotprobe.memory.v2.MemoryV2Repository
import com.local.wxbotprobe.memory.v2.ResolvedScopeInput
import com.local.wxbotprobe.memory.v2.toJsonObject
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class DebugMemoryV2SummarySchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val runId = UUID.randomUUID().toString()
        val realModel = intent?.getBooleanExtra(EXTRA_REAL_MODEL, false) == true
        Thread(
            {
                val result = runCatching {
                    runSchedulerTest(
                        context.applicationContext,
                        runId,
                        realModel,
                    )
                }
                val output = File(
                    context.filesDir,
                    "debug/memory-v2-summary-scheduler.json",
                )
                output.parentFile?.mkdirs()
                output.writeText(
                    result.fold(
                        onSuccess = { it.toString(2) },
                        onFailure = {
                            JSONObject()
                                .put("run_id", runId)
                                .put("real_model", realModel)
                                .put("status", "failed")
                                .put("error", it.message.orEmpty())
                                .put("stack", it.stackTraceToString())
                                .toString(2)
                        },
                    ),
                    Charsets.UTF_8,
                )
                android.util.Log.i("MemoryV2SummaryScheduler", output.readText())
                pending.finish()
            },
            "memory-v2-summary-scheduler",
        ).start()
    }

    private fun runSchedulerTest(
        context: Context,
        runId: String,
        realModel: Boolean,
    ): JSONObject {
        val databaseName = "memory-v2-summary-scheduler.db"
        context.deleteDatabase(databaseName)
        val database = MemoryV2Database(context, databaseName)
        val repository = MemoryV2Repository(context, database)
        val scope = repository.ensureScope(
            resolved = ResolvedScopeInput(
                key = MemoryScopeKey(
                    ownerId = "owner-summary-test",
                    contactId = "contact-summary-test",
                    agentId = "agent-summary-test",
                    personaId = "persona-summary-test",
                    realmId = "real",
                ),
                personaRevision = 1,
            ),
            contactDisplayName = "Summary Test",
        )
        val now = System.currentTimeMillis() / 1_000L
        var sessionId = ""
        val allMessageIds = mutableListOf<String>()
        val jobs = MemoryV2JobService(
            context = context,
            database = database,
            summaryMigrationEnabled = false,
        )
        val commits = MemoryV2BatchCommitService(context, database)
        val client = if (realModel) {
            ModelClientFactory.create(context)
                ?: error("model client is not configured")
        } else {
            null
        }
        val generator = client?.let {
            MemoryV2DeepSeekBatchGenerator(context, database, it)
        }
        val callBudget = MemoryV2CallBudgetStore(context)
        val config = MemoryV2ConfigLoader.load(context)
        val rounds = JSONArray()

        fun appendMessage(index: Int, markExtractionDone: Boolean = true) {
            val result = repository.appendMessage(
                scope,
                AppendMessageRequest(
                    clientMessageId = "summary-test-$index",
                    sessionId = sessionId.ifBlank { null },
                    role = if (index % 2 == 0) "user" else "assistant",
                    text = SUMMARY_CONVERSATION[index],
                    occurredAt = now + index,
                    receivedAt = now + index,
                    timezone = "Asia/Shanghai",
                ),
            )
            sessionId = result.sessionId
            allMessageIds += result.messageId
            if (markExtractionDone) {
                database.writableDatabase.execSQL(
                    "UPDATE messages SET extraction_state='done' " +
                        "WHERE scope_id=? AND message_id=?",
                    arrayOf(scope.scopeId, result.messageId),
                )
            }
        }

        fun commitQueued(roundName: String): JSONObject {
            val diagnostics = schedulerDiagnostics(database, scope.scopeId, sessionId)
            val queued = jobs.enqueueNextSummary(sessionId)
                ?: error("$roundName summary did not enqueue: $diagnostics")
            val job = jobs.leaseJob("debug-summary-scheduler", queued.jobId)
                ?: error("$roundName summary did not lease")
            val callNow = System.currentTimeMillis() / 1_000L
            if (realModel && !callBudget.tryAcquire(scope.scopeId, callNow)) {
                jobs.fail(job, MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED.name)
                error("$roundName hourly memory call budget is exhausted")
            }
            var raw = ""
            try {
                raw = if (realModel) {
                    requireNotNull(generator).generate(job)
                } else {
                    syntheticSummaryBatch(
                        database = database,
                        scopeId = scope.scopeId,
                        job.envelope.toJsonObject(),
                        job.envelope.newSourceIds,
                        roundName,
                    )
                }
                val batch = jobs.validateSummary(job, raw)
                if (batch.events.isEmpty() && batch.workingItems.isEmpty()) {
                    error("$roundName returned an empty summary")
                }
                commits.commitSummary(job, batch)
                return JSONObject()
                    .put("job_id", job.jobId)
                    .put("source_count", job.envelope.newSourceIds.size)
                    .put(
                        "base_working_summary_version",
                        job.envelope.baseWorkingSummaryVersion,
                    )
                    .put("event_count", batch.events.size)
                    .put("working_item_count", batch.workingItems.size)
                    .put("raw_utf8_bytes", raw.toByteArray(Charsets.UTF_8).size)
                    .put("result", "saved")
                    .also(rounds::put)
            } catch (error: Throwable) {
                val code = if (error is MemoryV2Exception) {
                    error.code.name
                } else {
                    MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name
                }
                jobs.fail(job, code)
                throw IllegalStateException(
                    "$roundName summary failed with $code; " +
                        "raw_model_output=$raw",
                    error,
                )
            }
        }

        repeat(7) { index ->
            appendMessage(index)
        }
        val beforeThreshold = jobs.enqueueNextSummary(sessionId)
        appendMessage(7)
        val first = commitQueued("first")
        repeat(7) { index ->
            appendMessage(index + 8)
        }
        val betweenCheckpoints = jobs.enqueueNextSummary(sessionId)
        appendMessage(15)
        val second = commitQueued("second")
        repeat(7) { index ->
            appendMessage(index + 16)
        }
        val betweenSecondAndThird = jobs.enqueueNextSummary(sessionId)
        appendMessage(23)
        val third = commitQueued("third")

        val db = database.readableDatabase
        val sessionState = db.query(
            "sessions",
            arrayOf("state"),
            "session_id=?",
            arrayOf(sessionId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        val workingState = db.rawQuery(
            "SELECT working_version,working_sources,working_summary " +
                "FROM sessions WHERE scope_id=? AND session_id=?",
            arrayOf(scope.scopeId, sessionId),
        ).use { cursor ->
            cursor.moveToFirst()
            Triple(
                cursor.getInt(0),
                JSONArray(cursor.getString(1)),
                cursor.getString(2),
            )
        }
        val workingVersion = workingState.first
        val coveredSources = workingState.second
        val workingSummary = workingState.third
        val coveredIds = buildList {
            for (index in 0 until coveredSources.length()) {
                add(coveredSources.getString(index))
            }
        }
        val workingSummaryRoot = runCatching {
            JSONObject(workingSummary)
        }.getOrElse { JSONObject() }
        val workingSummaryItemCount = WORKING_SECTIONS.sumOf { section ->
            workingSummaryRoot.optJSONArray(section)?.length() ?: 0
        }
        val summaryDone = db.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE scope_id=? AND session_id=? " +
                "AND summary_state='done'",
            arrayOf(scope.scopeId, sessionId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val extractionDone = db.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE scope_id=? AND session_id=? " +
                "AND extraction_state='done'",
            arrayOf(scope.scopeId, sessionId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val savedSummaryJobs = db.rawQuery(
            "SELECT COUNT(*) FROM jobs WHERE scope_id=? AND session_id=? " +
                "AND kind='summary' AND state='saved'",
            arrayOf(scope.scopeId, sessionId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val quickCheck = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        val foreignKeys = db.rawQuery("PRAGMA foreign_key_check", null).use {
            it.count
        }
        val roundDetails = (0 until rounds.length()).map {
            rounds.getJSONObject(it)
        }
        val roundSourceCounts = roundDetails.map { it.getInt("source_count") }
        val roundBaseVersions = roundDetails.map {
            it.getInt("base_working_summary_version")
        }
        val roundWorkingItemCounts = roundDetails.map {
            it.getInt("working_item_count")
        }
        val summaryBytes = workingSummary.toByteArray(Charsets.UTF_8).size
        val passed = beforeThreshold == null &&
            betweenCheckpoints == null &&
            betweenSecondAndThird == null &&
            sessionState == "open" &&
            roundSourceCounts == listOf(8, 8, 8) &&
            roundBaseVersions == listOf(0, 1, 2) &&
            roundWorkingItemCounts.all { it > 0 } &&
            coveredSources.length() == 24 &&
            coveredIds == allMessageIds &&
            workingVersion == 3 &&
            summaryDone == 24 &&
            extractionDone == 24 &&
            savedSummaryJobs == 3 &&
            workingSummaryItemCount > 0 &&
            summaryBytes <= config.workingSummaryMaxUtf8Bytes
        return JSONObject()
            .put("run_id", runId)
            .put("real_model", realModel)
            .put("api_call_count", if (realModel) 3 else 0)
            .put("status", if (passed) "passed" else "failed")
            .put("rounds", rounds)
            .put("session_state", sessionState)
            .put("working_version", workingVersion)
            .put("covered_source_count", coveredSources.length())
            .put("covered_sources_match_all_messages", coveredIds == allMessageIds)
            .put("working_summary_item_count", workingSummaryItemCount)
            .put("working_summary_utf8_bytes", summaryBytes)
            .put(
                "working_summary_max_utf8_bytes",
                config.workingSummaryMaxUtf8Bytes,
            )
            .put("summary_done", summaryDone)
            .put("extraction_done", extractionDone)
            .put("saved_summary_jobs", savedSummaryJobs)
            .put("seven_messages_before_first_round", beforeThreshold == null)
            .put("seven_messages_between_first_second", betweenCheckpoints == null)
            .put(
                "seven_messages_between_second_third",
                betweenSecondAndThird == null,
            )
            .put("quick_check", quickCheck)
            .put("foreign_key_violations", foreignKeys)
    }

    private fun schedulerDiagnostics(
        database: MemoryV2Database,
        scopeId: String,
        sessionId: String,
    ): JSONObject {
        val db = database.readableDatabase
        val now = System.currentTimeMillis() / 1_000L
        return JSONObject()
            .put("session_state", queryString(
                db,
                "SELECT state FROM sessions WHERE scope_id=? AND session_id=?",
                scopeId,
                sessionId,
            ))
            .put("pending_summary", queryLong(
                db,
                "SELECT COUNT(*) FROM messages WHERE scope_id=? " +
                    "AND session_id=? AND summary_state='pending' " +
                    "AND state='live'",
                scopeId,
                sessionId,
            ))
            .put("pending_extraction", queryLong(
                db,
                "SELECT COUNT(*) FROM messages WHERE scope_id=? " +
                    "AND session_id=? AND extraction_state='pending' " +
                    "AND state='live'",
                scopeId,
                sessionId,
            ))
            .put("eligible_cache", queryLong(
                db,
                "SELECT COUNT(*) FROM messages WHERE scope_id=? " +
                    "AND session_id=? AND summary_state='pending' " +
                    "AND state='live' AND cache_expires_at>?",
                scopeId,
                sessionId,
                now.toString(),
            ))
            .put("pending_jobs", queryLong(
                db,
                "SELECT COUNT(*) FROM jobs WHERE scope_id=? " +
                    "AND session_id=? AND state IN ('pending','leased')",
                scopeId,
                sessionId,
            ))
            .put("eligible_session", queryLong(
                db,
                "SELECT COUNT(*) FROM sessions s WHERE " +
                    "s.scope_id=? AND s.session_id=? AND " +
                    "(s.state='closed' OR (s.state='open' AND (" +
                    "SELECT COUNT(*) FROM messages threshold_m " +
                    "WHERE threshold_m.scope_id=s.scope_id " +
                    "AND threshold_m.session_id=s.session_id " +
                    "AND threshold_m.summary_state='pending' " +
                    "AND threshold_m.state='live' " +
                    "AND threshold_m.cache_expires_at>?" +
                    ")>=8)) AND EXISTS (" +
                    "SELECT 1 FROM messages m WHERE m.scope_id=s.scope_id " +
                    "AND m.session_id=s.session_id " +
                    "AND m.summary_state='pending' AND m.state='live' " +
                    "AND m.cache_expires_at>?" +
                    ") AND NOT EXISTS (" +
                    "SELECT 1 FROM messages m WHERE m.scope_id=s.scope_id " +
                    "AND m.session_id=s.session_id " +
                    "AND m.extraction_state='pending' AND m.state='live' " +
                    "AND m.cache_expires_at>?" +
                    ")",
                scopeId,
                sessionId,
                now.toString(),
                now.toString(),
                now.toString(),
            ))
            .put("threshold_gate", queryLong(
                db,
                "SELECT CASE WHEN (" +
                    "SELECT COUNT(*) FROM messages WHERE scope_id=? " +
                    "AND session_id=? AND summary_state='pending' " +
                    "AND state='live' AND cache_expires_at>?" +
                    ")>=8 THEN 1 ELSE 0 END",
                scopeId,
                sessionId,
                now.toString(),
            ))
            .put("summary_exists_gate", queryLong(
                db,
                "SELECT CASE WHEN EXISTS (" +
                    "SELECT 1 FROM messages WHERE scope_id=? " +
                    "AND session_id=? AND summary_state='pending' " +
                    "AND state='live' AND cache_expires_at>?" +
                    ") THEN 1 ELSE 0 END",
                scopeId,
                sessionId,
                now.toString(),
            ))
            .put("extraction_clear_gate", queryLong(
                db,
                "SELECT CASE WHEN NOT EXISTS (" +
                    "SELECT 1 FROM messages WHERE scope_id=? " +
                    "AND session_id=? AND extraction_state='pending' " +
                    "AND state='live' AND cache_expires_at>?" +
                    ") THEN 1 ELSE 0 END",
                scopeId,
                sessionId,
                now.toString(),
            ))
    }

    private fun queryLong(
        db: android.database.sqlite.SQLiteDatabase,
        sql: String,
        vararg args: String,
    ): Long {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun queryString(
        db: android.database.sqlite.SQLiteDatabase,
        sql: String,
        vararg args: String,
    ): String {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }
    }

    private fun syntheticSummaryBatch(
        database: MemoryV2Database,
        scopeId: String,
        envelope: JSONObject,
        sourceIds: List<String>,
        roundName: String,
    ): String {
        val firstSourceId = sourceIds.firstOrNull()
            ?: error("synthetic summary requires a source")
        val source = database.readableDatabase.rawQuery(
            "SELECT body,revision FROM messages " +
                "WHERE scope_id=? AND message_id=?",
            arrayOf(scopeId, firstSourceId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("synthetic summary source is unavailable")
            }
            cursor.getString(0) to cursor.getInt(1)
        }
        val working = JSONObject()
            .put(
                "goals",
                JSONArray().put(
                    JSONObject()
                        .put("text", "synthetic checkpoint $roundName")
                        .put(
                            "evidence",
                            JSONArray().put(
                                JSONObject()
                                    .put("message_id", firstSourceId)
                                    .put("source_revision", source.second)
                                    .put("quote", source.first),
                            ),
                        ),
                ),
            )
            .put("constraints", JSONArray())
            .put("unresolved", JSONArray())
            .put("references", JSONArray())
        return JSONObject()
            .put("contract", "SummaryBatch")
            .put("envelope", envelope)
            .put("processed_message_ids", JSONArray(sourceIds))
            .put("events", JSONArray())
            .put("working_summary", working)
            .toString()
    }

    companion object {
        private const val EXTRA_REAL_MODEL = "real_model"
        private val WORKING_SECTIONS = listOf(
            "goals",
            "constraints",
            "unresolved",
            "references",
        )
        private val SUMMARY_CONVERSATION = listOf(
            "这周六我们去逛公园吧，上午十点出发。",
            "好，我记下了，周六上午十点陪你逛公园。",
            "出门前提醒我带遮阳帽和水。",
            "会提醒你带遮阳帽和水。",
            "中午想去吃牛肉面。",
            "好，中午去吃牛肉面。",
            "如果下雨就改成室内咖啡店。",
            "下雨就改去室内咖啡店。",
            "周日还要记得给妈妈打电话。",
            "周日给妈妈打电话这件事也记下了。",
            "下午想买一些苹果和酸奶。",
            "好，下午买苹果和酸奶。",
            "家里的洗衣液快用完了。",
            "那这次采购也加上洗衣液。",
            "晚饭不要做太辣的菜。",
            "晚饭会做得清淡一些。",
            "下周三上午预约牙医复诊。",
            "下周三上午牙医复诊，已经记住。",
            "复诊前一天晚上提醒我早点睡。",
            "复诊前一晚提醒你早点睡。",
            "最近想每天散步三十分钟。",
            "每天散步三十分钟，从这周开始。",
            "不过周五晚上有加班，可能只能走十五分钟。",
            "周五按十五分钟安排，其他天保持三十分钟。",
        )
    }
}
