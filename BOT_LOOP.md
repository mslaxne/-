# Bot loop service

`BotLoopService` is the first always-on read loop.

## Behavior

- Foreground service with a persistent notification and stop action.
- Explicit `OFF / RUNNING / PAUSED / STOPPING` state is persisted.
- Pause stops polling without closing the active memory session. Resume keeps
  the same session. Stop closes it and writes the session summary.
- The notification and home screen expose separate pause/resume and stop
  actions.
- Waits three seconds after launch so the user can return to WeChat.
- First successful frame becomes the baseline; history is not treated as new.
- After an explicit start or resume, the first stable baseline is evaluated as a
  startup conversation. All visible `IN`/`OUT` bubbles are passed in screen
  order as a structured transcript; `UNKNOWN` bubbles are omitted.
- Startup evaluation triggers only when the bottom visible message is a readable
  incoming message. Outgoing or unknown-role bottom messages do not trigger a
  proactive reply.
- Startup history merges persisted current-session messages with the visible
  transcript. The visible tail is authoritative, overlap is deduplicated and
  older screen-only messages remain in order.
- Resume resets the polling baseline before startup evaluation so messages
  received while paused cannot also become a later duplicate burst reply.
- Each explicit start or resume binds the first stable recognized private chat
  as the only automatic-send target. There is no separate auto-send switch or
  contact whitelist. A chat switch, group or unknown chat pauses the Agent and
  preserves sessions and queues.
- Polls every three seconds through the selected `DeviceTransport` screenshot,
  RapidOCR and pixel rules.
- Runs OCR on every captured frame. Frame fingerprints remain diagnostic input,
  but small message bubbles are not trusted to cross a whole-screen diff
  threshold.
- Detects large image-message regions without requiring OCR text, so image-only
  incoming messages can still enter the freshness tracker. Region detection is
  not the burst reply payload.
- New incoming bubbles enter `IncomingTracker`.
- A four-second quiet window merges a burst of messages, with a 12-second
  maximum collection age.
- Completed bursts are written to `Android/data/.../files/inbox/`.
- When vision is enabled, burst completion copies the latest full WeChat-window
  screenshot once, persists it with the generation task and sends that image as
  the primary reply context. Retries and process recovery reuse the persisted
  screenshot rather than taking another one.
- A normal visual-model response is treated as reply text. An exact first
  control line of `WAIT` records no reply and waits for a future
  message-triggered burst; `SILENT` records no reply. JSON action objects from
  older prompts remain accepted. Recovery does not resubmit the same screenshot
  after a terminal wait or silent decision.
- If vision is disabled or screenshot persistence fails, the older text and
  image-region context path remains available.
- Poll events are logged to `Android/data/.../files/logs/bot-loop.log`.
- A completed burst is sent through the model and, when enabled, through the
  persistent delivery queue.
- Polling and every automatic delivery part are gated by the running state.
  Pause or stop during a multi-part send keeps the unsent queue in place.
- The OCR title plus a measured title-bar fingerprint scopes history and the
  pending queue to one chat. Switching chats resets the incoming baseline.
- Agent sessions load persistent per-chat memory on open and summarize the
  session when explicitly stopped or when the chat changes. See `MEMORY.md`.
- After a process restart, a persisted running/stopping state is presented as
  paused. The open session is reattached on resume rather than summarized only
  because the process disappeared.

## vivo runtime requirements

Two system conditions were required on the V2055A:

1. Device-idle battery allowlist.
2. This app's accessibility service enabled as a keepalive.

The second is a vivo-specific workaround: without it, the process was moved to
the `/frozen` cgroup even though the data-sync foreground service and its
notification were alive. Enabling the local AccessibilityService moved it to
`l-foreground`. Route C also uses that service for screenshots and taps.

The app now refuses to start the loop when either condition is missing and
opens the relevant setting.

Route C additionally requires the Accessibility transport service, BotIme to be
enabled, and BotIme to be the system default. Automatic writing still refuses
to run unless BotIme receives the WeChat input connection. See `ROUTE_C.md`.

## Verified

- Baseline on the real WeChat chat: 9 bubbles, 2 incoming.
- Foreground notification remained active while the poll loop ran.
- Stop action shut down the service cleanly.
- `AgentRunPolicy` has pure JVM tests for pause/stop transitions, stale-state
  recovery and send gating.
- Burst state machine has pure Kotlin tests for baseline suppression, incoming
  collection and quiet-window merging.
- P0 dry-run passed with the chat guard enabled.
- Moving the screen away from WeChat cancelled the dry-run with
  `无法识别当前聊天标题，发送已取消`.
- A simulated partial delivery retained `nextPartIndex=1` after a failed send
  attempt and did not advance to the last bubble.
