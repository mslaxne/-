# Memory 2.0 Android integration checkpoint

Date: 2026-10-28

## Source

- Framework package: external development bundle `AI_Memory_Framework_v2`
- Vendored contract assets:
  `app/src/main/assets/memory_v2/`
- The package's offline validation was reproduced from a workspace copy:
  - contracts: 18/18 passed
  - SQLite reference acceptance: 97/97 passed
  - quality evaluator self-test: 4/4 passed
  - real LLM calls: `not_run`

## Current implementation status

Memory 2.0 is the **only production memory path**. Production no longer
constructs `ChatMemoryStore` or `AgentMemoryManager`; legacy classes remain
isolated historical tooling and are not referenced by the app UI or coordinator.

Current public source candidate: `1.1.3` (versionCode `163`). Route C and Route B
share the same Memory V2 implementation described here.

Implemented Android foundation:

- exact v2 `schema.sql`, JSON Schema, predicates, model profile and prompts are
  bundled as read-only assets;
- `MemoryV2Database` creates the 19-table SQLite schema with foreign keys, WAL,
  full synchronization and a short busy timeout;
- `MemoryV2IdentityStore` generates stable local owner, agent, persona and
  restore-guard IDs;
- `MemoryV2ScopeResolver` maps a stable v2 contact ID into the scope and derives
  a stable positive persona revision;
- `MemoryV2Repository` can create scopes/entities and atomically append a
  message with idempotency, sequence assignment and bounded resource ledgers;
- `MemoryV2QuoteLocator` computes strict UTF-8/SHA-256 and Unicode scalar quote
  offsets without fuzzy matching;
- `MemoryV2TokenBudgetPolicy` fails closed for unknown/stale model profiles,
  unsupported request modes, invalid output budgets and exhausted contexts.
- deterministic explicit commands now support save (including correction via
  `expected_version`), pin/unpin and forget;
- command operations use Keystore HMAC fact keys/request receipts, short
  transactions, evidence locality, history, tombstones, dependency closure,
  job cancellation and outbox generation.
- persisted extraction/summary job envelopes now support idempotent enqueue,
  lease/retry state, strict envelope/source/batch validation and empty-batch
  completion;
- scoped retrieval assembles A0/A1/A2 context with query matching and captured
  deletion epoch without reinforcing evidence.
- startup reply context merges persisted session history with the ordered visible
  screen transcript but does not bulk-import old visible messages into memory;
- a completed burst may be supplied to a vision-capable reply model as one
  short-lived full-window screenshot. The screenshot and model-generated visual
  descriptions are not written to Memory V2 evidence. OCR continues to persist
  text messages and the `[[图片]]` placeholder for image-only items, so Memory
  extraction cannot infer unseen image content from the reply screenshot;
- the triggering incoming message uses a deterministic client message ID derived
  from the startup snapshot so retries and process restarts are idempotent;
- pending conflicts are supplied as context only. The reply path does not
  generate a per-turn conflict verdict, recommended reply or replacement text;
- validated extraction and summary batches commit atomically. Extraction
  candidates carry an LLM-selected A0/A1/A2 tier; new or changed A0 memory stays
  in `needs_review` until explicitly approved. Summary events remain A2 and the
  A3 working summary/version/coverage update in the same transaction.
- extraction context includes all active A0/A1, query-matched A0/A2, the previous
  A3 summary and up to 16 recent extracted sources. A3 and recent sources are
  reference-only and cannot be cited as evidence.
- production extraction exposes only scoped read tools to the Memory Agent:
  search/list active memories, read recent session messages and list pending
  conflicts. It then submits a complete ExtractionBatch through
  `memory.commit_batch` or outputs the batch directly. Raw SQL, arbitrary scope
  IDs, hard deletes and cross-contact reads are not exposed.
- one Memory Agent extraction job reserves at most four model calls in the hourly
  budget: three read-only tool calls and one final submission. Summary jobs remain
  single-call and do not receive database tools.
- the model returns a phase checkpoint. `closed=false` consumes no source;
  `closed=true` consumes only the earliest contiguous closed prefix and leaves
  the trailing sources pending for a later evaluation.
- layered reply-context rendering labels event time separately from memory update
  time. A0/A1 use temporal metadata when available and otherwise expose the
  original source-message time; A3 items include the time range of their covered
  sources. The context supplies absolute time plus current system time, leaving
  relative-age interpretation to the LLM.
- production-shaped background worker now adds persistent per-scope/device hourly
  call budgets, job leasing, retry/failure handling and a DeepSeek batch
  generator that uses the vendored prompts/model profile/token budget.
- terminal model-request and storage failures get at most three fresh extraction
  generations after a 60-second cooldown. Failed jobs remain in history and
  pending source messages are not deleted or rewritten.
- a no-progress open-phase result with the same source set is deferred even when
  the session is already closed, preventing repeated calls on identical context.
- the Memory page provides one `立即整理记忆` action. It drains pending extraction
  first and then summary checkpoints with at most 8 sources per job. Manual mode
  bypasses the saved open-phase decision, failed-job cooldown, memory hourly
  quota and daily API soft cap while still recording actual token spend.
  Structural, evidence and storage failures stop the run and leave pending
  counts visible.
- legacy JSON migration tooling now has stable contact mapping, a read-only dry-run, and
  explicitly approved per-item checkpoints. The importer preserves source time,
  blocks missing-time sources, revalidates source evidence and TTL, enforces
  quotas, records per-item receipts, updates capacity ledgers, resumes missing
  receipts and is idempotent.
- the production Memory page reads v2 contacts, raw message counts, long-term
  memories and commitments directly; migration and rollback controls have been
  removed.
- Route C and Route B each pass 499 JVM tests; both Lint reports are clean.
- On-device debug self-test passed: 19 contract tables, foreign keys enabled,
  scope creation, message sequence 1, duplicate client message `unchanged`,
  explicit save version 1, pin version 2, three operation receipts, forget
  epoch 1, two tombstones and source state `forgotten`.
- The 1.0.58 self-test passed session close, transaction-sourced legacy import,
  replay `unchanged`, four non-zero capacity ledgers, activation and rollback.
  That historical run ended on the legacy read path before the 1.0.65 reset.
- User-authorized real-device migration imported 89 items, quarantined 33 and
  blocked none for contact `A`; activation and rollback both succeeded.
- The first real v2 append exposed a missing session-row bug. 1.0.60 now lets
  the first append create the session and binds the returned ID. The fixed live
  path logged `memory v2 append role=user seq=1` without error.
- A user-authorized controlled v2 auto-reply to the File Transfer Assistant
  passed through real DeepSeek generation, delivery queue and Route C sending.
  Exactly one `收到` bubble was sent; the log recorded assistant `seq=1` in
  session `39b5c4f6-753f-47bd-b2b9-8ea2f8c880eb` and `delivery sent parts=1`.
- Normal stop changed that session to `closed` with `next_seq=5`. A forced
  process restart preserved the closed session and converted persisted agent
  state `RUNNING` to `PAUSED`; an explicit stop then returned `OFF`.
- The stop-path run exposed a remaining race: cached OCR appended three user
  messages as `seq=2..4` and generated one local unsent reply after `loop stop`.
  Auto-send was OFF, so no second bubble was queued or sent. Temporary unsent
  artifacts were removed.
- At the end of the 1.0.61 verification, auto-send was OFF, the agent was
  stopped, the whitelist contained one contact and the read path was `LEGACY`.
  Those test records were later deleted by the authorized 1.0.65 reset.
- `1.0.61` recovers the latest open v2 session for a scope, closes duplicate
  open sessions, and reuses the recovered ID for the next append. The on-device
  isolated self-test recovered a session and continued with assistant `seq=2`.
- Normal app shutdown now closes all persistent v2 open sessions. Explicit stop
  also cancels pending reply tasks and fences in-flight generation before
  persistence or delivery.
- `1.0.62` adds persistent outbox leasing, bounded retry/backoff, expired-lease
  reconciliation, transactional ACK deletion and capacity-ledger refresh. The
  current local processor is a no-op because retrieval reads SQL directly; a
  future external index can replace the processor. On-device selftest drained
  three events, retried one injected failure and reconciled one expired lease.
- `1.0.64` checkpoints legacy migration per item. A stored pending/failed run
  resumes missing receipts, skips committed items and becomes `committed` only
  when every plan item has a receipt. Each item transaction rechecks scope
  state and deletion epoch. On-device selftest paused with one receipt, resumed
  the same run, and replayed as `unchanged`.
- `1.0.65` removes the production legacy read path, switches Home and whitelist
  to v2 admin data, and enqueues extraction/summary jobs from live v2 sessions.
  With user confirmation, all device memory data and test artifacts were
  deleted; the app relaunched at 0 contacts, 0 messages and 0 memories.
- A Debug-only lifecycle probe now passes real `force-stop` recovery in an
  isolated database: SQLite/WAL integrity, open-session ID and `next_seq`,
  idempotent append and expired extraction-job lease recovery all passed before
  and after process death. The test database was cleaned and Release restored.
  This does not yet substitute for coordinator-owned live-chat, reboot,
  storage-pressure or backup-restore tests.

Still required before sustained v2 operation:

- deletion barriers, history/dependency cleanup and source editing;
- external-index processor integration when an index is introduced;
- coordinator-owned live-chat process-kill recovery, plus reboot,
  storage-pressure and backup-restore fencing;
- Android SQLite migration tests, phone lifecycle/performance tests and broader
  real-model memory quality evaluation.

## Existing call chain mapping

| Existing component | Memory 2.0 mapping |
|---|---|
| `ChatIndexEntry.chatId` | stable `contact_id`; never use display name |
| local installation identity | persisted `owner_id` and `agent_id` |
| current persona profile | stable `persona_id` plus content-derived `persona_revision` |
| current real account chat | `realm_id=real` |
| `AgentSession.sessionId` | v2 `session_id` |
| incoming `SessionMessage` | role=user message |
| delivered reply parts | role=assistant messages |
| existing `MemoryEvent` / session summaries | migration candidates; no automatic A0/A1 promotion |
| `PersonaProfileStore` | trusted A0 configuration authority, not LLM-derived memory |
| `MemoryContextBuilder` | future v2 context builder; current path remains unchanged |

## Activation gates

1. All framework deterministic checks continue to pass after any vendored asset
   change.
2. Route C and Route B JVM tests pass.
3. Android SQLite creates all tables and a migration round-trip passes on the
   target device.
4. Existing JSON contacts produce an explicit dry-run mapping with no unknown
   realm or evidence promoted silently. Implemented and exercised on the real
   snapshot: 89 imported, 33 quarantined.
5. A feature flag can switch read paths atomically without dual recall.
   Implemented and exercised on the real device, including rollback.
6. Real phone tests cover process kill, reboot, lock, disk pressure and backup
   restore fencing.
7. Real DeepSeek extraction/summary/answer quality is reported from actual
   calls; synthetic fixtures are not reported as model quality.
