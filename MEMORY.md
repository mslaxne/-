# Agent memory

The bot uses explicit agent sessions. Memory is loaded when a session opens and
written back when it closes; messages are not summarized one at a time.

## Lifecycle

- A session opens after the first successful WeChat screenshot for a recognized
  chat while the bot loop is running.
- Switching to another chat detaches the previous session into `closing` state,
  starts the new session immediately and queues the old session for background
  summarization.
- Stopping the bot loop detaches the active session and returns the agent to
  OFF immediately. It does not wait for the summary or keep the foreground
  service alive.
- Pausing never closes or summarizes the active session.
- If the process dies with an open session, resuming the same chat reattaches
  that session. Process death alone is not treated as an explicit stop.
- Stopping after a process restart closes any remaining open sessions before
  the service exits.
- A summary interrupted while already closing is retried because that session
  had passed the explicit stop boundary.
- The loop is paused whenever the foreground package is not `com.tencent.mm`.
  Opening this app to inspect status therefore does not create a fake chat
  memory.

## Storage

Everything is stored in app-internal storage:

```text
files/memory/
  index.json
  chats/{chatId}.json
  sessions/{sessionId}.json
  recaps/{chatId}/{sessionId}.md
  recaps/{chatId}/{sessionId}.json
```

`index.json` maps a normalized OCR chat title to a stable UUID, its chat file,
aliases and per-contact retention policy. Each chat file contains session
summaries and structured events. Each open session contains its raw message
references and delivery status. Files are replaced atomically.

Memory index, chat, session and recap mutations share a process-wide transaction
lock across all `ChatMemoryStore` instances. Summary model calls run outside the
lock; completion reloads the latest session and memory inside the lock and
merges the result before saving.

The display summary archive is separate from model memory. Each session gets a
markdown recap and a metadata sidecar under its contact directory. The metadata
stores the local session start/end timestamps and the rendered summary.

The current identity key is the normalized title visible in the WeChat header.
Two contacts with the exact same displayed name therefore share memory until
manual alias/disambiguation is added. Renaming a chat starts a separate memory
entry.

## Model context

When opening a chat, the app builds a bounded system note containing:

- up to three recent completed session summaries
- up to eight selected events or pending items
- no complete transcript dump

Events are ranked using importance, recency and unresolved status. This creates
selective recall without randomly deleting facts. The note is capped at 1,500
characters.

The current session continues to use the normal 12-turn working history. Only
actually delivered assistant bubbles are committed to history and session
memory.

## Session summary

`MemorySummaryWorker` processes one closing/pending session per run and calls
DeepSeek with a separate low-temperature summary prompt. The response is parsed
into:

- a short overall summary
- topics
- invitations, todos, promises, facts and open questions
- status, normalized date, importance and source message IDs

Long sessions are compacted in chunks by the worker before the final structured
summary. This still produces one user-visible summary per agent session.

A markdown recap is written to `files/memory/recaps/` and can be viewed from
the app. Summary failure leaves the session pending for retry instead of losing
the raw record.

The worker reads persisted `closing` sessions and closed sessions whose summary
status is `pending`. It retries failures after 30 seconds and waits 60 seconds
when no API key is available. Active/open sessions are excluded from this queue
so contact switching cannot summarize the current chat accidentally.

Each summary claim persists a process run ID. A second summary of the same
session in the current process is skipped. A `running` claim left by a dead
process becomes reclaimable on restart. If the session or contact was deleted
while the model call was in flight, completion does not recreate it.

## Retention and deletion

Retention belongs to each contact, not to the whole app. A contact can be set to
1, 3, 7, 31 days or permanent independently. New contacts default to 7 days;
old index entries without a retention field are migrated to the same default.
Changing one contact immediately prunes only that contact's completed sessions,
events and raw session files. Permanent mode retains source data, but the model
still sees only the bounded summary/event selection.

Event cleanup protects:

- every unresolved event, regardless of age;
- every event dated today or in the future;
- resolved events only until their normal last-mentioned retention cutoff.

This prevents a future invitation or unfinished todo from disappearing because
it has not been mentioned again recently.

Display summaries use a separate per-contact policy: most recent 10/30/50
entries, most recent 7/30/90 days, or permanent. Pruning this archive removes
only markdown summaries and metadata, not structured model memory.

The contact session-summary dialog lists each saved session with its local
completion date and duration computed from `closedAt - openedAt`. New sessions
capture both timestamps with `System.currentTimeMillis()` at open and detach.

The contact management dialog can delete one contact's index entry, chat file,
session files and recap files without touching other contacts. Individual
deletion is rejected while the bot loop is not stopped. `清空全部聊天记忆`
remains available as a separate global destructive action.

## Verification

- Unit tests cover stable chat UUIDs, session persistence, partial/open recovery,
  TTL pruning, permanent retention, event deduplication, context ranking,
  structured summary parsing and deferred assistant commits.
- Regression tests distinguish paused-session reattachment from explicit-stop
  summarization.
- Tests cover per-contact retention persistence, pruning one contact without
  changing another, and deleting one contact while preserving other histories.
- The app UI shows each contact's retention in its row and exposes per-contact
  retention and deletion from the contact management dialog. Latest recap and
  full-memory deletion remain separate global actions.
- A real-device session test produced one memory chat without creating a fake
  entry from the app itself.
- Foreground-package checking logs `poll paused outside WeChat` when the app is
  brought forward during a run.
