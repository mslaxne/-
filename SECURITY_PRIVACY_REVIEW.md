# Handora security and privacy review

Last reviewed: 2026-10-27.

Current source candidate: `1.0.130` (154). Device validation is still required.

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

### Startup visible-conversation context

The startup path sends readable visible `IN`/`OUT` messages to the configured
model when the bottom message is incoming. It merges those messages with the
persisted current-session transcript and retains an app-private startup JSON
snapshot. Identity, roleplay, prompt-injection and real-world capability rules
are prompt-only: no deterministic post-check or fixed reply replacement exists.
Those guarantees therefore depend on model compliance.

### Extraction reference context

Each extraction request may send all active A0/A1 memory, query-matched A0/A2
memory, the previous A3 working summary and up to 16 recent extracted source
messages to the configured model. A3 and recent sources are marked reference-only
and cannot be used as evidence. New and changed A0 memory requires explicit
human approval; persona/profile/config fields remain program-protected.

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

- Route C and Route B each pass `455` JVM tests with zero failures/errors.
- Both Lint reports say `No issues found.`
- Release APK inspection confirms the backup attributes and all exclusion
  domains.
- Final device state: Home `6/6`, Agent stopped, Accessibility Bound, BotIme
  default, HTTP proxy `null`, pending deliveries `0`, reply retries `0`.
- No real contact message was sent.

Evidence:
`build-artifacts-vivo-security-privacy-1.0.123/verification.json`.
