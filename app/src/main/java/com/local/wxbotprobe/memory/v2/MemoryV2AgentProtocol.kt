package com.local.wxbotprobe.memory.v2

import org.json.JSONObject

sealed interface MemoryV2AgentTurn {
    data class ToolCall(
        val name: String,
        val arguments: JSONObject,
    ) : MemoryV2AgentTurn

    data class FinalBatch(
        val json: String,
    ) : MemoryV2AgentTurn

    data class Invalid(
        val raw: String,
    ) : MemoryV2AgentTurn
}

object MemoryV2AgentProtocol {
    const val MAX_READ_TOOL_CALLS = 3

    val databaseCatalog: JSONObject = JSONObject(
        """
        {
          "protocol_version":"memory-agent-1.0.0",
          "scope":"Every tool is bound to the current contact/persona scope.",
          "sessions":"Rolling A3 working summary, version, state and covered source IDs.",
          "messages":"Raw user/assistant source text, time, hash, extraction/summary state.",
          "memories":"Active A0/A1/A2 rows with IDs, versions, predicate, evidence anchor and expiry.",
          "evidence":"Immutable quotes that support memory operations.",
          "memory_conflicts":"Pending semantic conflicts awaiting a later turn or review.",
          "jobs":"Persistent extraction/summary leases and retry state."
        }
        """.trimIndent(),
    )

    val instructions: String = """
        You are the Memory Agent for the current scope. You may inspect memory through
        the read-only tools below before deciding what to remember. You never receive
        raw SQL and cannot address another scope.

        Tools:
        - memory.search {"query":"text","tiers":["A0","A1","A2"],"limit":12}
        - memory.list_active {"tiers":["A0","A1","A2"],"limit":12}
        - session.read_recent {"limit":12}
        - memory.pending_conflicts {}
        - memory.commit_batch {"arguments": <complete ExtractionBatch>}

        Each turn output exactly one JSON object. To use a read-only tool:
        {"tool":"memory.search","arguments":{"query":"...","limit":8}}

        To finish, submit the complete ExtractionBatch through:
        {"tool":"memory.commit_batch","arguments":{...ExtractionBatch...}}

        Alternatively, output the ExtractionBatch JSON directly. Use tools only when
        the initial context is insufficient. At most three read-only tool calls are allowed.
        The program validates evidence, scope, versions, predicates, transactions and
        A0 review. Do not invent IDs or claim a write succeeded before commit.
    """.trimIndent()

    const val FINAL_INSTRUCTION =
        "Tool inspection is complete. Submit the final ExtractionBatch now through " +
            "memory.commit_batch or output the ExtractionBatch JSON directly. " +
            "Do not request more tools."

    fun parse(raw: String): MemoryV2AgentTurn {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return MemoryV2AgentTurn.Invalid(raw)
        }
        val root = runCatching {
            JSONObject(raw.substring(start, end + 1))
        }.getOrNull() ?: return MemoryV2AgentTurn.Invalid(raw)
        if (root.optString("contract") == "ExtractionBatch") {
            return MemoryV2AgentTurn.FinalBatch(root.toString())
        }
        val tool = root.optString("tool").trim()
        if (tool.isEmpty()) {
            return MemoryV2AgentTurn.Invalid(raw)
        }
        val arguments = root.optJSONObject("arguments") ?: JSONObject()
        if (tool == "memory.commit_batch") {
            return if (arguments.optString("contract") == "ExtractionBatch") {
                MemoryV2AgentTurn.FinalBatch(arguments.toString())
            } else {
                MemoryV2AgentTurn.Invalid(raw)
            }
        }
        return MemoryV2AgentTurn.ToolCall(tool, arguments)
    }
}
