# UI layout

The app is now a configuration center; day-to-day control is primarily through
the overlay bubble.

## Home

- Compact runtime summary: readiness, bubble, auto-send, queue and memory count.
- One dynamic `开启代理 / 停止代理` button.
- Three secondary entrances: `聊天记忆`, `运行设置`, `诊断工具`.
- No permission, screenshot or send-test buttons on the home screen.

## Memory

- Retention period and aggregate memory counts.
- Change retention and view the latest completed recap.
- Per-contact rows with summary and pending-event counts.
- Clear-all action remains on this page with confirmation.

## Settings

Grouped into:

- agent settings: overlay bubble, auto-send and DeepSeek key
- deployment and permissions: setup guide, Shizuku, battery, Accessibility and
  input-method actions
- advanced: diagnostics entry

Auto-send uses a persistent checkbox and turns red with an `已开启` label.

## Diagnostics

The old capture, OCR, tap, dry-run and test-send actions live here. The page also
contains the latest screenshot preview and current status.

## Navigation

- Home has a gear icon at the top-right.
- Settings and Memory use a top-left back arrow.
- Diagnostics is reached from `设置 → 高级 → 诊断工具`.
- The Android system back button follows the same page stack.

## Verification

- Home displays the agent toggle, one memory entrance and the top-right gear.
- Memory, Settings and Diagnostics switched correctly on the V2055A.
- The compact home summary shows `运行环境 6/6`, bubble, auto-send, queue and
  memory in four lines.
- Auto-send was toggled off and persisted through the new settings screen.
- The gear icon and back arrow both passed accessibility-content-description
  checks, and system back returned Settings to Home.
