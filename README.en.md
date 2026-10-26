# Handora 1

[简体中文](README.md) | English

Android 13 side-load prototype for the WeChat handoff project. It reads the
screen, generates replies with DeepSeek, and can deliver them through the
custom input method. Device actions support both the existing Shizuku transport
and a first-stage route-C Accessibility transport. Auto-send is off by default.

> This is a public source repository, not an official Tencent or WeChat product.
> APK files are not committed to source control; test builds may be distributed
> through GitHub Releases. Use the software only with accounts and devices you
> are authorized to operate, and comply with WeChat terms, local law, and
> Accessibility-service rules. Do not use it for harassment, spam,
> impersonation, or non-consensual automation.

## Capabilities

- MediaProjection screen capture with a five-second delay.
- Accessibility gesture click at `(538, 2320)`.
- Custom InputMethodService commit of `这是测试`.
- Shizuku UserService screenshot and input tap in the separate `routeB` build.
- Route C screenshot, gesture tap, foreground package and global actions through
  the local AccessibilityService.
- A persisted route selector; route C is the default runtime path.
- Resident BotIme writing for route C without programmatic IME switching.
  Route C is headless: it does not show an input view or request soft-keyboard
  insets, so WeChat keeps its normal input-row position while the app writes.
- BotIme rechecks the active editor package inside the commit and clear calls,
  so a focus switch away from `com.tencent.mm` is rejected before text changes.
- Offline RapidOCR Android recognition behind the `OcrEngine` interface.
- Low-memory screen scanning uses a row-cached `BitmapPixelSource` instead of
  full-screen `IntArray` copies. OCR runs on every captured frame because small
  incoming bubbles can remain below a whole-screen scene-difference threshold.
- Short incoming text is deduplicated while it remains visible, then re-armed
  after the tracker observes the bubble leave and reappear. Long-text fuzzy
  matching and the per-snapshot occurrence count are unchanged.
- Route C send screenshots start at a 1.5-second interval and automatically
  back off to 3.5 seconds if Android reports rate limiting. Polling remains on
  its existing three-second cadence.
- Screenshot/OCR polling runs on its own worker while model generation and
  delivery run on a serialized action worker. Device screenshots and sends
  share a lock so send-time keyboard movement does not contaminate OCR.
- Foreground polling, baseline suppression and three-second burst collection.
- Persona-aware DeepSeek reply generation with identity/injection guardrails.
- Persistent reply-generation retries for model errors, with five bounded
  attempts, 5/10/20/40-second backoff and a visible Home queue count.
- Auto-send hard limits: normalized contact whitelist, daily sent-bubble cap,
  daily API-cost cap based on actual token usage, and configurable quiet hours.
- Reply splitting, custom-IME writing and Shizuku send-tap confirmation.
- The measured fast-send point is limited to an exact screen/density/WeChat
  version profile. Unknown profiles detect the green button center in the
  current screenshot instead of tapping a fixed coordinate. BotIme has no manual
  test-write button.
- A shared `DeviceProfile` supplies normalized input anchors, message guards,
  fast OCR regions, field-band bounds and green-button search radii. The verified
  vivo layout keeps measured pixels; unknown displays use bounded proportions.
- Settings -> Device adaptation offers a local screenshot calibration. The App
  validates a visible input row and green send button, stores a profile scoped
  to screen/density/orientation/font scale/WeChat version, and deletes the
  screenshot after parsing.
- Startup checks separate hard requirements from reliability warnings. Missing
  notification, transport, IME or API key blocks the dependent capability;
  unavailable battery-exemption controls or unscaled device layouts only warn.
- Settings -> Model configuration supports DeepSeek, free-tier/custom
  OpenAI-compatible providers and disabled generation. Free tiers use the
  user's own provider key; no shared API key is bundled.
- If BotIme is enabled but not selected, Route C opens the system input-method
  picker; only the disabled-IME case opens input-method management.
- During an active send, chat-title pixels are no longer rechecked because WeChat
  replaces the title with `对方正在输入中`. Foreground package and BotIme editor
  package checks remain. Users must not switch chats during an automatic send.
- Explicit agent sessions with per-chat memory, close-time event summaries and
  configurable 1/3/7/31-day or permanent retention.
- Contact switching and stopping detach the active session immediately. A
  single background memory worker summarizes closing/pending sessions with
  persisted recovery and visible queue progress.
- Each contact has a human-readable summary archive with local start/end
  timestamps, duration, and independent retention by day count or entry count.
- Memory retention protects unresolved events and any event dated today or in
  the future, even when its last-mention timestamp is older than the cutoff.
- A draggable overlay bubble with separate start/pause, stop and collapse
  controls.
- A Home top-left `退出` action that stops polling, removes the bubble, cancels
  notifications, attempts a bounded memory close and then exits even if memory
  summarization fails or times out.
- A light native Home / Memory / Settings layout with diagnostics moved into an
  advanced section. See `UI_LIGHT.md`.

## Code boundaries

- `AgentStartPolicy` owns the testable startup decision and setup destination.
- `AgentControl` is the single start/pause/resume/stop entry used by Home, the
  overlay bubble and diagnostics.
- `HomePageView` owns Home rendering; `MainActivity` keeps navigation,
  permissions and lifecycle.
- `MemoryPageView`, `SettingsPageView` and `DiagnosticsPageView` own their page
  rendering, refresh and page-local actions.
- `BotLoopService` owns the foreground-service lifecycle and orchestration.
  `PollingCoordinator`, `ReplyGenerationCoordinator`, `MemoryCoordinator` and
  `SendQueueCoordinator` own their respective loop responsibilities.
- `ReplyRetryStore` persists failed generation jobs under
  `files/reply-retries/`; retries remain scoped to the originating chat.
- `AppShutdownCoordinator` owns the independent local-first exit path. It does
  not route through the normal stop-and-summarize page or wait indefinitely for
  memory summarization.
- `ImeWritePolicy` is the final package guard used immediately before
  `commitText` and `deleteSurroundingText`.
- `MainUiComponents` contains shared native UI primitives.
- `ProbeStatus` is app-wide diagnostics state independent of the Activity.

## Build

Open this directory in Android Studio, or build with Gradle after pointing
`local.properties` at an Android SDK that has platform 35 and build-tools 35.

For the complete route-C build, test, lint, install, and state-restoration gate:

```powershell
.\deploy.ps1 -Flavor routeC
```

```powershell
gradlew.bat :app:assembleRouteCRelease
```

APK output:

```text
app/build/outputs/apk/routeC/release/app-routeC-release.apk
```

Run the pure-logic regressions and lint:

```powershell
gradlew.bat :app:testRouteCDebugUnitTest :app:lintRouteCDebug
```

The default `routeC` APK excludes Shizuku's provider, AIDL and API
dependencies. Build the rollback variant with:

```powershell
.\deploy.ps1 -Flavor routeB
```

```text
app/build/outputs/apk/routeB/release/app-routeB-release.apk
```

## Device setup

1. Install and open the APK.
2. Allow notifications.
3. Tap the screenshot button and approve whole-screen capture.
4. Enable `Handora 1` under Android accessibility settings.
5. Enable `Handora 1 输入法` and set BotIme as the system default keyboard.
6. Keep WeChat in the foreground for delayed capture and click tests.
7. Add the app to the system battery-optimization allowlist, and also allow
   auto-start/background activity in vivo's iManager if available.
8. On the target vivo build, keep the app's accessibility service enabled as a
   background keepalive. Route C uses it for screenshots and gestures.

## Route B fallback build

Install and start Shizuku 13.6.0 or later, then use the app's
`连接 / 授权 Shizuku` action. The privileged UserService runs as shell UID and
implements only `screencap` and `input tap`.

Shizuku controls are only compiled into the `routeB` flavor. The default
`routeC` flavor has no Shizuku provider, AIDL or API dependency.

The RapidOCR AAR is stored in `app/libs/`; see `THIRD_PARTY_NOTICES.md`.

## Route C setup

1. Enable the app's AccessibilityService.
2. Enable BotIme and select it as the system default keyboard.
3. Stop the agent and enable `路线 C 无障碍传输` in Settings.
4. Confirm the foreground chat is WeChat, then run the existing dry-run.

Route C does not select or restore IMEs. If BotIme is not the active default,
the route-C start and test entries refuse early; the sender also refuses to
write if the input connection is not WeChat. See `ROUTE_C.md`.

The screenshot is stored in the app-private `files/captures` directory.

Configure the DeepSeek key from `配置 DeepSeek API Key`. It is encrypted with
Android Keystore and is never read from this repository or an `.env` file.
