package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.ContentValues
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

class DebugMemoryV2ExtractProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val messageId = intent?.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID).orEmpty()
        val databaseName = intent?.getStringExtra(EXTRA_DATABASE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "memory-v2.db"
        val execute = intent?.getBooleanExtra(EXTRA_EXECUTE, false) == true
        val pending = goAsync()
        Thread(
            {
                val result = runCatching {
                    require(execute) { "execute=true is required" }
                    require(messageId.isNotBlank() || jobId.isNotBlank()) {
                        "message_id or job_id is required"
                    }
                    runProbe(
                        context.applicationContext,
                        messageId.takeIf { it.isNotBlank() },
                        jobId.takeIf { it.isNotBlank() },
                        databaseName,
                    )
                }
                val output = File(
                    context.filesDir,
                    "debug/memory-v2-extract-probe.json",
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
                android.util.Log.i("MemoryV2ExtractProbe", output.readText())
                pending.finish()
            },
            "memory-v2-extract-probe",
        ).start()
    }

    private fun runProbe(
        context: Context,
        messageId: String?,
        jobId: String?,
        databaseName: String,
    ): JSONObject {
        val database = MemoryV2Database(context, databaseName)
        val jobs = MemoryV2JobService(context, database)
        val commits = MemoryV2BatchCommitService(context, database)
        val client = ModelClientFactory.create(context)
            ?: error("model client is not configured")
        val generator = MemoryV2DeepSeekBatchGenerator(context, database, client)
        val budget = MemoryV2CallBudgetStore(context)
        val rounds = JSONArray()
        val now = System.currentTimeMillis() / 1_000L
        if (jobId != null) {
            val updated = database.writableDatabase.update(
                "jobs",
                ContentValues().apply {
                    put("state", "pending")
                    put("attempts", 0)
                    putNull("lease_owner")
                    putNull("lease_until")
                    put("next_attempt_at", now)
                    put("updated_at", now)
                },
                "job_id=? AND kind='extract' " +
                    "AND state IN ('failed','pending','leased','cancelled')",
                arrayOf(jobId),
            )
            require(updated == 1) { "failed extraction job was not found" }
        }

        repeat(MAX_ROUNDS) { roundIndex ->
            val pendingJobId = jobId ?: pendingExtractionJob(database, messageId!!)
                ?: return@repeat
            val job = jobs.leaseJob("debug-extract-probe", pendingJobId)
                ?: return@repeat
            val round = JSONObject()
                .put("round", roundIndex + 1)
                .put("job_id", job.jobId)
                .put("source_count", job.envelope.newSourceIds.size)
            rounds.put(round)
            if (!budget.tryAcquire(job.scopeId, now)) {
                jobs.fail(job, MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED.name)
                round.put("status", "failed")
                round.put("error", MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED.name)
                return JSONObject()
                    .put("status", "passed")
                    .put("message_id", messageId ?: JSONObject.NULL)
                    .put("job_id", jobId ?: JSONObject.NULL)
                    .put("rounds", rounds)
            }
            var rawOutput = ""
            try {
                rawOutput = generator.generate(job)
                val batch = jobs.validateExtraction(job, rawOutput)
                val candidates = JSONArray()
                batch.candidates.forEach { candidate ->
                    val tier = if (candidate.reason == "recent_event") "A2" else "A1"
                    val evidence = JSONArray()
                    candidate.evidence.forEach {
                        evidence.put(
                            JSONObject()
                                .put("message_id", it.messageId)
                                .put("quote", it.quote),
                        )
                    }
                    candidates.put(
                        JSONObject()
                            .put("reason", candidate.reason)
                            .put("tier", tier)
                            .put("predicate", candidate.predicate)
                            .put("text", candidate.text)
                            .put("value", candidate.valueJson)
                            .put("epistemic", candidate.epistemic)
                            .put("evidence", evidence)
                            .put(
                                "target_evidence",
                                candidate.evidence.any { it.messageId == messageId },
                            ),
                    )
                }
                if (batch.candidates.isEmpty()) {
                    jobs.completeEmpty(job, batch)
                } else {
                    commits.commitExtraction(job, batch)
                }
                round
                    .put("status", "model_completed")
                    .put("candidate_count", batch.candidates.size)
                    .put("candidates", candidates)
                return JSONObject()
                    .put("status", "passed")
                    .put("message_id", messageId ?: JSONObject.NULL)
                    .put("job_id", jobId ?: JSONObject.NULL)
                    .put("rounds", rounds)
            } catch (error: MemoryV2Exception) {
                if (error.code == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED) {
                    budget.release(job.scopeId, now)
                }
                round
                    .put("status", "model_error")
                    .put("error", error.code.name)
                    .put("error_detail", error.message.orEmpty())
                if (rawOutput.isNotBlank()) {
                    round.put("raw_model_output", rawOutput)
                }
                if (
                    error.code == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED
                ) {
                    val children = jobs.splitContextOverflow(job)
                    if (children.isNotEmpty()) {
                        round.put("split_job_ids", JSONArray(children))
                        return@repeat
                    }
                }
                jobs.fail(job, error.code.name)
                return JSONObject()
                    .put("status", "passed")
                    .put("message_id", messageId)
                    .put("rounds", rounds)
            }
        }
        return JSONObject()
            .put("status", "passed")
            .put("message_id", messageId ?: JSONObject.NULL)
            .put("job_id", jobId ?: JSONObject.NULL)
            .put("rounds", rounds)
            .put("exhausted_rounds", true)
    }

    private fun pendingExtractionJob(
        database: MemoryV2Database,
        messageId: String,
    ): String? {
        return database.readableDatabase.rawQuery(
            "SELECT j.job_id FROM jobs j WHERE j.kind='extract' " +
                "AND j.state='pending' AND EXISTS (" +
                "SELECT 1 FROM dependencies d WHERE d.scope_id=j.scope_id " +
                "AND d.parent_kind='message' AND d.parent_id=? " +
                "AND d.child_kind='job' AND d.child_id=j.job_id" +
                ") ORDER BY (" +
                "SELECT COUNT(*) FROM dependencies c WHERE c.scope_id=j.scope_id " +
                "AND c.parent_kind='message' AND c.child_kind='job' " +
                "AND c.child_id=j.job_id" +
                ") ASC,j.created_at DESC LIMIT 1",
            arrayOf(messageId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    companion object {
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_DATABASE_NAME = "database_name"
        private const val EXTRA_EXECUTE = "execute"
        private const val MAX_ROUNDS = 8
    }
}
