package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.memory.v2.LeasedMemoryJob
import com.local.wxbotprobe.memory.v2.MemoryV2BackgroundWorker
import com.local.wxbotprobe.memory.v2.MemoryV2BatchGenerator
import com.local.wxbotprobe.memory.v2.MemoryV2ErrorCode
import com.local.wxbotprobe.memory.v2.MemoryV2Exception
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import java.io.File
import org.json.JSONObject

class DebugMemoryV2OverflowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread(
            {
                val result = runCatching {
                    val recovery = MemoryV2JobService(context.applicationContext)
                        .splitFailedOverflowBatch()
                    val worker = MemoryV2BackgroundWorker(
                        context.applicationContext,
                        generator = object : MemoryV2BatchGenerator {
                            override fun generate(job: LeasedMemoryJob): String {
                                throw MemoryV2Exception(
                                    MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED,
                                    "debug fake generator",
                                )
                            }
                        },
                    )
                    JSONObject()
                        .put("status", "passed")
                        .put("recovery_changed", recovery.changed)
                        .put("recovery_job_id", recovery.splitJobId ?: JSONObject.NULL)
                        .put("recovery_error", recovery.errorCode ?: JSONObject.NULL)
                        .put("changed", worker.runOnce("debug-memory-overflow"))
                }
                val output = File(
                    context.filesDir,
                    "debug/memory-v2-overflow.json",
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
                android.util.Log.i("MemoryV2Overflow", output.readText())
                pending.finish()
            },
            "memory-v2-overflow",
        ).start()
    }
}
