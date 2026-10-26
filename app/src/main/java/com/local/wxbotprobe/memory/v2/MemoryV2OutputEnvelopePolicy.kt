package com.local.wxbotprobe.memory.v2

import org.json.JSONObject

object MemoryV2OutputEnvelopePolicy {
    fun matchesCompact(
        actual: JSONObject?,
        expectedJobId: String,
    ): Boolean {
        return actual != null &&
            actual.length() == 1 &&
            actual.optString("job_id") == expectedJobId
    }
}
