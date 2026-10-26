# Agent bubble

The agent can be controlled from a small draggable overlay without opening the
main activity. The collapsed circular state has one job: open the control panel.
It never starts, pauses or stops the agent directly.

## Behavior

- A circular 58dp bubble is shown through `TYPE_APPLICATION_OVERLAY`.
- The collapsed bubble uses a dark translucent fill, a 2dp state outline, a
  subtle tinted shadow and a white three-dot control glyph.
- A tap expands the bubble; a drag moves it. A drag never starts or stops the
  agent.
- The expanded panel is 186dp wide with three 56dp action cells separated by
  hairlines. It mirrors its order to stay inside the screen:
  - right edge: `collapse | start/pause | stop`
  - left edge: `start/pause | stop | collapse`
- Action glyphs use one custom line-icon set. The stop glyph is coral when
  enabled, while collapse remains neutral grey.
- Pressing an action cell scales and dims its glyph until release, giving touch
  feedback without changing the parent touch-zone logic.
- The collapsed circle uses its outline for state:
  - running: green breathing outline
  - paused: steady yellow outline
  - stopping and summarizing: steady blue outline
  - off: steady grey outline
- The expanded panel shows a media-play action for off/paused and media-pause
  for running. The stop action requires a long press of at least 700ms so a
  stray tap cannot discard the active session.
- The panel auto-collapses after five seconds.
- Releasing a dragged bubble snaps it to the nearest left or right edge and
  saves the side and vertical position.
- The bubble remains visible after the agent stops.
- The foreground notification can hide the bubble.
- The bubble preference persists and is restored after reboot if overlay
  permission is still granted.

## Run state

`BotLoopService` persists `OFF`, `RUNNING`, `PAUSED` or `STOPPING`.

- Pause stops polling but keeps the memory session open and does not generate a
  summary.
- Resume continues the same session.
- Stop enters `STOPPING`, closes the current session, writes the summary and
  then returns to `OFF`.
- If the process is killed while running or stopping, the next UI process shows
  `PAUSED` instead of a stale green running state. No summary is generated just
  because the process died.

## Permissions

- `SYSTEM_ALERT_WINDOW`
- `RECEIVE_BOOT_COMPLETED`
- A low-importance foreground-service notification

The overlay control is enabled from Settings. If overlay permission is missing,
the app opens vivo's permission screen.

## Agent start checks

The panel refuses to start the agent and shows a short message when Shizuku is
not connected, Accessibility keepalive is off, the battery allowlist is missing,
or the DeepSeek key is not configured.

## Verification

- The overlay permission screen opened correctly on the V2055A.
- The bubble rendered at the right-center edge and was detected from real
  screenshots.
- Tapping the circle expanded the panel without changing the foreground app.
- The 186dp expanded panel showed two hairline dividers aligned exactly with
  the three touch zones.
- The green breathing outline, yellow paused outline, coral stop glyph and
  neutral collapse glyph were verified from real screenshots.
- Dragging to the left edge produced the mirrored `start | stop | collapse`
  layout; the inner collapse cell returned it to 58dp.
- Right-side start, pause, resume and long-press stop were exercised on the
  device.
- Dragging to the left edge persisted `side=left`; the mirrored panel layout and
  its inner collapse zone were verified.
- The foreground notification provides a "关闭气泡" action.
