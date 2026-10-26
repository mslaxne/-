# Memory framework review checkpoint

Date: 2026-10-23

## v2 review update

The complete v2 package was supplied as a separate development bundle and
contains 70 files, including the
DDL, config, contracts, predicates, prompts, executable acceptance suite,
migration/token tools, invalid fixtures and reports.

The v2 specification is a substantial improvement. It now defines the
single-device/multi-contact scope mapping, a zero-extra-synchronous-LLM-call
chat path, trusted quote-offset calculation, explicit token-budget failure
modes, a broader persistence model, bounded infrastructure tables, and stronger
migration/deletion/outbox requirements.

The package's offline validation was reproduced from a workspace copy on
2026-10-23:

- contracts and consistency checks: 18/18 passed;
- SQLite reference acceptance: 97/97 passed;
- quality evaluator self-test: 4/4 passed;
- real LLM calls: `not_run`.

Decision: adopt v2 as the Android memory design authority and implement behind a
default-off feature flag. The initial Android foundation is now in
`app/src/main/java/com/local/wxbotprobe/memory/v2/`. Keep `ChatMemoryStore`
active until command extraction, retrieval, background work, migration and
on-device SQLite tests are complete.

## Source package

- Full package: external development bundle `AI_Memory_Framework_v1`
- Main specification: `AI_Memory_Framework_v1.md`
- Companion files reviewed:
  - `memory_config.yaml`
  - `memory_contracts.schema.json`
  - `contract_examples.json`
  - `prompts.md`
  - `acceptance_cases.json`
  - `CODEX_TASK.md`
  - `README.md`
  - `VALIDATION.md`
- The companion specification copy reviewed during development used the same
  content hash as the file in the full package.

## Current decision

The framework is a valid design authority, but it has not been integrated or
enabled as the application's core memory system. The current `ChatMemoryStore`
remains active.

The user's GPT quota is exhausted. The next step is to have GPT revise the
package using the prompt below, then review the revised package before starting
the Android implementation.

## Required revisions

1. Map the framework's `(user_id, agent_id, realm_id)` scope to a single-device,
   multi-contact Android bot. Clarify the stable contact ID, session ID, global
   agent/persona, and real/story realm boundaries. Cross-contact retrieval must
   remain forbidden.
2. Add a complete durable storage contract for scopes, entities, aliases,
   sessions, messages, memories, evidence, dependencies, jobs, outbox,
   tombstones, capacity ledger, audit events, and scheduled tasks. Define
   SQLite transactions, unique constraints, indexes, recovery, and migration.
3. Separate synchronous explicit memory commands from ordinary-chat extraction.
   Ordinary chat cannot synchronously wait up to 2500 ms for an extra LLM call
   on every turn. Define deterministic prefilters, asynchronous extraction,
   latency/cost budgets, and explicit saved/pending/failed states.
4. Make token budgeting executable for DeepSeek. The package currently has
   `model_context_limit_tokens: null` and `require_model_tokenizer: true` without
   an adapter. Define conservative estimation, validation, and fail-closed
   behavior when the model window or tokenizer is unknown.
5. Do not ask the LLM to calculate Unicode code-point offsets. The model should
   output the exact quote; trusted application code should locate the quote and
   calculate `start_codepoint`/`end_codepoint`.
6. Add session/job envelope context to summary contracts, including explicit
   session identity and expected working-summary version checks.
7. Convert natural-language acceptance assertions into machine-readable expected
   states, errors, and data mutations. Add failure injection for idempotency,
   stale jobs, forget/in-flight races, restart recovery, outbox failures, and
   scheduler failures.
8. Ship the invalid fixtures and a runnable contract-validation harness so the
   claims in `VALIDATION.md` can be reproduced.
9. Define LLM-quality sample sizes, model/prompt versions, scoring rules, and
   pass thresholds. Mark unexecuted quality tests as not run.
10. Add capacity limits for outbox, tombstones, entities/aliases, and related
    infrastructure. Resolve conflicts among framework TTLs, existing source
    retention, and JSON-to-SQLite migration.

## Prompt for GPT

```text
请直接修订我提供的 AI 聊天机器人记忆框架交接包，不要只给建议。保留 A0-A3、I01-I20、证据溯源、版本控制、删除屏障、配额与原子提交原则，并保持所有文件彼此一致。

重点修正：
1. 明确 Android 单机多联系人场景的 scope 映射：联系人稳定 ID、会话 ID、agent/人格配置、real/story realm 如何隔离；禁止跨联系人检索，并说明现有旧记忆如何迁移。
2. 补充完整持久层契约：scopes、entities、aliases、sessions、messages、memories、evidence、dependencies、jobs、outbox、tombstones、capacity_ledger、audit_events、scheduled_tasks 的字段、唯一约束、事务边界、索引和恢复规则。以 SQLite 为参考实现。
3. 修正热路径：明确“记住/忘记/更正”等命令可同步处理；普通聊天使用确定性预筛和异步提取。给出延迟、API 调用和 pending/saved/failed 状态约束，避免每轮额外调用 LLM。
4. 修复 tokenizer 与模型窗口契约：给出 DeepSeek 可执行的 token 估算/校验方案，未知窗口或不可靠 tokenizer 时的明确降级行为。
5. 修改候选提取契约：LLM 只输出精确原文 quote，禁止自行计算 Unicode 偏移；由可信程序定位并生成 start/end。SummaryBatch 增加或明确 session_id、job envelope 和版本上下文校验。
6. 将 acceptance_cases.json 改为可执行断言：明确预期状态、错误码、数据变化和事务结果；补齐幂等、旧任务、遗忘、重启、outbox、调度失败等 failure_injection。
7. 随包提供可运行的契约校验器、无效契约样例和测试命令，使 VALIDATION.md 的结论可以重复验证。
8. 为 LLM 质量评估增加固定样本、模型/提示词版本、评分规则和通过阈值；未实际运行的项目必须标为未运行。
9. 补齐 outbox、tombstone、实体别名等资源的容量限制，并解决框架 TTL、宿主原文保留期和现有 JSON 记忆迁移之间的冲突。

交付修订后的完整文件、变更说明和可运行校验结果；不要声称未执行的测试已经通过。
```

## Next-session order

1. Completed: obtained and reviewed the complete v2 package; reproduced its
   offline validation.
2. Completed: mapped the revised model to:
   `BotLoopService -> MemoryCoordinator -> AgentMemoryManager ->
   ChatMemoryStore -> MemoryContextBuilder`.
3. Completed foundation: vendored contract assets, 19-table SQLite helper,
   stable scope mapping, message ingestion, quote locator and token budget.
   On-device debug SQLite self-test passed.
4. Completed: deterministic save/correct/pin/forget commands, operation receipts,
   history/dependencies and deletion/forget. On-device debug self-test passed.
5. Completed: persistent extraction/summary jobs, lease/retry state, frozen
   envelope validation and empty-batch completion. Scoped retrieval/context
   assembly is also implemented; on-device debug self-test passed.
6. Completed: atomic application of non-empty validated extraction/summary
   batches and A3 working-summary commit. A production-shaped background worker,
   call budget and DeepSeek generator are implemented but feature-disabled.
7. Completed implementation (feature-disabled): background worker, persistent
   call budget, DeepSeek batch generator, prompt/profile/token-budget wiring and
   legacy JSON planner/import. Real DeepSeek/tokenizer calibration remains.
8. Completed: confirmed legacy JSON import, per-contact mapping UI, quota and
   ledger writes, idempotent replay, atomic feature-flagged read-path activation
   and rollback. The real snapshot imported 89 items and quarantined 33. A live
   v2 user append exposed and fixed the session-binding defect in 1.0.60, after
   which the path logged `memory v2 append role=user seq=1` without error.
9. Next requires explicit send permission: controlled File Transfer Assistant
   auto-reply delivery, v2 assistant append, close/restart recovery and real
   DeepSeek token/quality calibration.
10. Keep cross-platform Android calibration as a separate pending milestone.

## Non-negotiable constraints

- Do not replace or delete existing contact memories during migration without a
  tested backup/rollback path.
- Do not let LLM output modify scope, permissions, A0 authority, quotas,
  protections, or deletions.
- Do not log private message text, exact evidence quotes, API keys, or full
  memory contents by default.
- Do not start the agent, switch the IME, or send a real WeChat message without
  explicit permission.
