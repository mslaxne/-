package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.memory.v2.MemoryV2BatchCommitService
import com.local.wxbotprobe.memory.v2.MemoryV2CallBudgetStore
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2DeepSeekBatchGenerator
import com.local.wxbotprobe.memory.v2.MemoryV2ErrorCode
import com.local.wxbotprobe.memory.v2.MemoryV2Exception
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class DebugMemoryV2BacklogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val execute = intent?.getBooleanExtra(EXTRA_EXECUTE, false) == true
        val maxJobs = intent?.getIntExtra(EXTRA_MAX_JOBS, 3) ?: 3
        val pending = goAsync()
        Thread(
            {
                val result = runCatching {
                    require(execute) { "execute=true is required" }
                    runBacklog(context.applicationContext, maxJobs)
                }
                val output = File(
                    context.filesDir,
                    "debug/memory-v2-backlog.json",
                )
                output.parentFile?.mkdirs()
                output.writeText(
                    result.fold(
                        onSuccess = { it.toString(2) },
                        onFailure = {
                            JSONObject()
                                .put("status", "failed")
                                .put("error", it.message.orEmpty())
                                .put("stack", it.stackTraceToString())
                                .toString(2)
                        },
                    ),
                    Charsets.UTF_8,
                )
                android.util.Log.i("MemoryV2Backlog", output.readText())
                pending.finish()
            },
            "memory-v2-backlog",
        ).start()
    }

    private fun runBacklog(context: Context, maxJobs: Int): JSONObject {
        val database = MemoryV2Database(context)
        val jobs = MemoryV2JobService(context, database)
        val commits = MemoryV2BatchCommitService(context, database)
        val client = ModelClientFactory.create(context)
            ?: error("model client is not configured")
        val generator = MemoryV2DeepSeekBatchGenerator(context, database, client)
        val budget = MemoryV2CallBudgetStore(context)
        val now = System.currentTimeMillis() / 1_000L
        val rounds = JSONArray()
        for (index in 0 until maxJobs.coerceIn(1, 6)) {
            jobs.enqueueMissingExtraction()
            jobs.splitFailedOverflowBatch()
            val jobId = nextExtractionJob(database)
            val job = jobId?.let {
                jobs.leaseJob("debug-backlog", it)
            } ?: break
            val round = JSONObject()
                .put("job_id", job.jobId)
                .put("source_count", job.envelope.newSourceIds.size)
            rounds.put(round)
            if (!budget.tryAcquire(job.scopeId, now)) {
                jobs.fail(job, MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED.name)
                round
                    .put("status", "budget_exhausted")
                    .put("error", MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED.name)
                break
            }
            try {
                val batch = jobs.validateExtraction(
                    job,
                    generator.generate(job),
                )
                if (batch.candidates.isEmpty()) {
                    jobs.completeEmpty(job, batch)
                } else {
                    commits.commitExtraction(job, batch)
                }
                round
                    .put("status", "saved")
                    .put("candidate_count", batch.candidates.size)
                    .put(
                        "tiers",
                        JSONArray(batch.candidates.map {
                            if (it.reason == "recent_event") "A2" else "A1"
                        }),
                    )
            } catch (error: MemoryV2Exception) {
                if (error.code == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED) {
                    budget.release(job.scopeId, now)
                }
                round
                    .put("status", "model_error")
                    .put("error", error.code.name)
                if (
                    error.code in setOf(
                        MemoryV2ErrorCode.STALE_EPOCH,
                        MemoryV2ErrorCode.STALE_SUMMARY,
                    )
                ) {
                    jobs.cancelForReschedule(job, error.code)
                } else if (
                    error.code == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED
                ) {
                    jobs.splitContextOverflow(job)
                } else {
                    jobs.fail(job, error.code.name)
                }
            }
        }
        val summaryRounds = JSONArray()
        for (index in 0 until maxJobs.coerceIn(1, 6)) {
            val queued = jobs.enqueueNextSummary() ?: break
            val job = jobs.leaseJob("debug-summary-backlog", queued.jobId)
                ?: break
            val round = JSONObject()
                .put("job_id", job.jobId)
                .put("source_count", job.envelope.newSourceIds.size)
            summaryRounds.put(round)
            try {
                val batch = jobs.validateSummary(
                    job,
                    generator.generate(job),
                )
                commits.commitSummary(job, batch)
                round
                    .put("status", "saved")
                    .put("event_count", batch.events.size)
                    .put("working_item_count", batch.workingItems.size)
            } catch (error: MemoryV2Exception) {
                round
                    .put("status", "model_error")
                    .put("error", error.code.name)
                jobs.fail(job, error.code.name)
                break
            }
        }
        val db = database.readableDatabase
        val scopeId = db.rawQuery(
            "SELECT scope_id FROM scopes WHERE state='active' LIMIT 1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
        }
        val counters = db.rawQuery(
            "SELECT extraction_state,COUNT(*) FROM messages " +
                "WHERE scope_id=? AND state='live' GROUP BY extraction_state",
            arrayOf(scopeId),
        ).use { cursor ->
            JSONObject().apply {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), cursor.getInt(1))
                }
            }
        }
        val memories = db.rawQuery(
            "SELECT tier,COUNT(*) FROM memories WHERE scope_id=? " +
                "AND lifecycle='active' GROUP BY tier",
            arrayOf(scopeId),
        ).use { cursor ->
            JSONObject().apply {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), cursor.getInt(1))
                }
            }
        }
        val pendingJobs = db.rawQuery(
            "SELECT COUNT(*) FROM jobs WHERE scope_id=? " +
                "AND kind='extract' AND state IN ('pending','leased')",
            arrayOf(scopeId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        return JSONObject()
            .put("status", "passed")
            .put("rounds", rounds)
            .put("summary_rounds", summaryRounds)
            .put("extraction_states", counters)
            .put("active_memories", memories)
            .put("pending_extraction_jobs", pendingJobs)
    }

    private fun nextExtractionJob(database: MemoryV2Database): String? {
        return database.readableDatabase.rawQuery(
            "SELECT j.job_id FROM jobs j WHERE j.kind='extract' " +
                "AND j.state='pending' ORDER BY (" +
                "SELECT COUNT(*) FROM dependencies d " +
                "WHERE d.scope_id=j.scope_id AND d.child_kind='job' " +
                "AND d.child_id=j.job_id AND d.parent_kind='message'" +
                ") ASC,j.created_at ASC LIMIT 1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    companion object {
        private const val EXTRA_EXECUTE = "execute"
        private const val EXTRA_MAX_JOBS = "max_jobs"
    }
}
