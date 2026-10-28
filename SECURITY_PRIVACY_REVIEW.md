# 代理 security and privacy review

Last reviewed: 2026-10-28.

Current public candidate: `1.0.131` (155).

This is a focused static and release-artifact review, not a security
certification. It covers backups, debug exports, API-key storage, chat-title
logging, runtime-artifact cleanup, proxy state and core exported services.

## Resolved in 1.0.123

- `android:allowBackup="false"` and `android:fullBackupContent="false"`.
- Cloud backup and device transfer explicitly exclude:
  - `root`, `file`, `database`, `sharedpref`, `external`;
  - `device_root`, `device_file`, `device_database`, `device_sharedpref`.
- The built Release APK contains no Debug receivers and is not debuggable.
- Agent logs no longer write chat titles. They retain chat fingerprint and
  title length only.
- Runtime-data cleanup now includes `diagnostics/` and calibration screenshots.
- API keys are encrypted with AES/GCM using an Android Keystore key. Diagnostics
  do not contain plaintext API keys.
- Core foreground services and screen capture are not exported. Accessibility
  and IME services are exported only with their required system bind
  permissions.

## Open product decisions

### Full-window vision input

Vision is a manual, model-scoped option and is off by default. It is invalidated
when the provider, endpoint or model ID changes. When enabled, each completed
burst copies the current full WeChat window once and sends that screenshot
through the configured OpenAI-compatible `image_url` content channel.

The screenshot can include the status bar, notifications, contact title, visible
history and images embedded in the chat. The app must not claim selective or
privacy-safe screen processing. Screenshots are limited to short-lived,
app-private retry attachments while a reply task is active and are not written
to Memory V2.

Natural visual output is treated as reply text. An exact first control line of
`WAIT` or `SILENT` is a terminal no-reply decision and is saved with the task so
recovery does not resubmit the same screenshot. JSON action objects remain
accepted for compatibility. If vision is disabled or screenshot persistence
fails, the older text and image-region path remains available.

### Startup visible-conversation context

The startup path sends readable visible `IN`/`OUT` bubbles, in screen order, to
the configured model when the bottom bubble is incoming. It does not send
navigation, input-field or unknown-role OCR text. Those messages are merged with
the persisted current-session transcript, and an app-private startup JSON
snapshot is retained. Older visible messages are not bulk-imported into Memory.

Identity, roleplay, prompt-injection and real-world capability rules are
prompt-only: no deterministic post-check or fixed reply replacement exists.
Those guarantees therefore depend on model compliance.

### Automatic-send authorization

Starting or resuming the Agent is the automatic-send authorization. The first
stable recognized private chat becomes the only send target; there is no
separate switch or contact allowlist. A detected chat switch, group title or
unknown chat pauses the Agent and preserves queues. An in-flight send can still
race a chat switch inside WeChat, so the foreground-package and BotIme editor
checks remain mandatory and users must not switch chats during an active send.

### Memory Agent and extraction context

Each extraction request may send all active A0/A1 memory, query-matched A0/A2
memory, the previous A3 working summary and up to 16 recent extracted source
messages to the configured model. A3 and recent sources are marked reference-only
and cannot be used as evidence. New and changed A0 memory requires explicit
human approval; persona/profile/config fields remain program-protected.

The Memory Agent receives only scoped, read-only tools for memory search/list,
recent session messages and pending conflicts. It cannot pass a scope ID or raw
SQL. It finishes through a validated `memory.commit_batch` intent or a direct
ExtractionBatch. The program still enforces evidence quotes, source locality,
versions, transactions, idempotency, A0 review and persona/config protection.
One extraction job is budgeted for at most four model calls: three read tools and
one final submission.

The extraction model selects A0, A1 or A2 for each validated candidate. The
model also decides whether an early conversation phase has ended; the program
only enforces contiguous prefix coverage, evidence locality, idempotency and the
retry threshold. Reply-context rendering labels event time separately from
memory update time and supplies absolute dates, leaving relative-age
interpretation to the LLM.

### P2: Runtime artifacts default to permanent retention

`RuntimeDataSettings` defaults to retention `0` and size limit `0`, meaning
permanent/unlimited. Screenshots, replies, outbox records, logs and benchmark
outputs can remain until the user selects a policy. Changing the default would
delete pre-existing user data, so this requires an explicit product decision
and migration policy.

### P2: Local runtime data is not encrypted at rest

Images, reply JSON, delivery records and logs are stored in app-private storage
as plaintext. Android sandboxing protects ordinary cross-app access, but local
forensics, rooted devices and privileged backups remain outside that boundary.

### P2: Accessibility access is broad by design

The service can retrieve window content, perform gestures and take screenshots.
It is user-enabled and protected by the system bind permission, but the product
still needs a clear disclosure and accepted privacy policy for screen-content
processing.

## Verification

- Route C and Route B each pass `491` JVM tests with zero failures/errors.
- Both Lint reports say `No issues found.`
- Release APK inspection confirms the backup attributes and all exclusion
  domains.
- Debug and Release builds pass for Route C and Route B.
- The public verification did not send a message to a real contact.
