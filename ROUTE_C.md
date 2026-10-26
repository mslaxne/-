# Route C transport prototype

Route C is the default runtime path. The default `routeC` APK contains no
Shizuku provider, AIDL or API dependency. A separate `routeB` APK keeps the
rollback implementation.

## Transport boundary

`DeviceTransport` owns only device actions:

- `screenshot(File)`
- `tap(x, y)`
- `foregroundPackage()`
- `performGlobalAction(BACK | HOME)`

Implementations:

- `ShizukuTransport` delegates to the existing privileged UserService.
- `AccessibilityTransport` uses the enabled local AccessibilityService.

IME handling is separate under `ImeStrategy`:

- `ShizukuImeStrategy` preserves route B's enable, select and restore behavior.
- `ResidentBotImeStrategy` never switches IME or calls `Settings.Secure`.
- Route C BotIme is headless. It keeps the `InputConnection` but returns no
  visible input view and declines soft-keyboard display requests. This prevents
  WeChat from applying keyboard insets or moving its input row upward.

`BotLoopService` and `WeChatSender` no longer call device operations on
`ShizukuShell` directly.

## Accessibility implementation

`ProbeAccessibilityService` provides:

- `takeScreenshot()` into a PNG file.
- `dispatchGesture()` for coordinate taps with completion confirmation.
- `performGlobalAction()` for back and home.
- foreground package tracking from `TYPE_WINDOW_STATE_CHANGED` and WeChat
  content-change events, with active-window package recovery after service
  restart or package replacement.

The service ignores window events produced by enabled IME packages and by its
own IME, overlay and dialog windows. Without this filter, a keyboard event such
as `com.baidu.input_vivo` was mistaken for the foreground application and
cancelled a safe send.

`canRetrieveWindowContent` is enabled so the service can recover the active
window's package name after a package update. The implementation reads only the
package name; it does not read node text or use window content for recognition.

Screen geometry is supplied by `DeviceProfile`. The verified vivo profile keeps
the original measured pixels. Other displays use proportional input, OCR and
button-search geometry and stay on dynamic button detection.

Accessibility screenshots are paced at 1.5 seconds between requests. If Android
reports screenshot rate limiting, the pacing interval is raised to 3.5 seconds
for the lifetime of the process and the failed request is retried after the
same backoff. Three-second polling remains slower than this base pacing, while
the sender's short burst of safety screenshots can complete with less latency.
The polling coordinator runs RapidOCR on every captured frame. Whole-screen
frame reuse was removed from message detection because a small incoming bubble
can remain below the scene-change threshold and otherwise go unread.

Route C requires the foreground package to be explicitly `com.tencent.mm`.
An unknown package blocks the operation.

## IME boundary

Route C requires BotIme to be enabled and selected as the system default before
the agent or a test send can start. `Settings.Secure.DEFAULT_INPUT_METHOD` is
read only to verify this state; the App never changes it.

The readiness model has separate checks:

- BotIme is present in the enabled input-method list.
- BotIme is the system default input method.

The deployment panel uses both checks for route C, so an enabled-but-not-default
keyboard cannot produce a ready count of `7/7`.

After tapping the input box, the strategy verifies that BotIme received an
input connection for `com.tencent.mm`. If another keyboard is active, the
sender fails with:

```text
当前默认输入法不是 BotIme，路线 C 已拒绝写入。
```

The draft cleanup path remains `commitText` / `clearText`; clipboard paste was
not introduced.

The sender tracks two separate states:

- the IME preparation flow has started;
- the WeChat input connection was actually opened through BotIme.

Only the second state permits a final BACK key. A failure in IME preparation
before focus therefore cannot navigate away from the WeChat chat.

Route C never sends BACK to dismiss its input view because no visible keyboard
is raised. Route B retains BACK dismissal for the visible Shizuku-managed
keyboard.

## Switching and rollback

Settings contains `路线 C 无障碍传输`. It defaults to on and is persisted.
Switching requires the agent to be stopped.

To roll back:

1. Stop the agent.
2. Disable `路线 C 无障碍传输`.
3. Reconnect Shizuku.
4. Route B restores the previous default IME after a send.

Packaging is now split:

- `routeC`: route C only; no Shizuku provider, AIDL, API classes or package
  query.
- `routeB`: route C plus the complete Shizuku fallback.

Installing either flavor uses the same application ID and signing key, so app
data and the encrypted API key are preserved when switching builds.

## Real-device validation

Device: vivo V2055A, Android 13, 1080x2400, density 480.

- Accessibility screenshot: 60/60 successful, zero black frames.
- Capture latency: min 302 ms, P50 329 ms, P95 343 ms, max 355 ms.
- Three-second polling: no Accessibility screenshot rate-limit errors.
- Same-scene pixel comparison: 99.9964% identical, mean absolute error 0.00269.
- PSS stabilized around 908-919 MB after the first samples; the final sample was
  about 9 MB below the starting PSS.
- `dispatchGesture` focused the WeChat input; `mInputShown=true`.
- Route C dry-run wrote two parts, found the green send button, cleared the
  draft, returned with the keyboard hidden and did not send.
- With Shizuku force-stopped, route C completed screenshot, RapidOCR, two draft
  writes, draft cleanup and gesture tap.
- With Baidu selected as default, route C refused to write and left Baidu
  selected.
- Route C loop baseline worked with Shizuku stopped. Pause preserved the
  session, resume reused it, and stop recorded a clean stop.
- A 32-minute soak kept PID `13535` for all samples. Every sample had
  `freezer:/`, with `cpuset:/foreground`.
- Soak PSS: min 901,487 KB, P50 913,685 KB, P95 987,714 KB, max 998,651 KB.
  Values oscillated with OCR work and returned near 900 MB rather than growing
  monotonically.
- Soak logs contained no screenshot rate-limit errors. Eight poll failures were
  all the expected locked/off-screen guard while the display slept.
- Starting with BotIme enabled but not default is rejected before BotLoop starts
  and before a dry-run taps the input.
- Starting with BotIme default but no focused WeChat input is allowed; writing
  still requires `ImeBridge.isTargetPackage("com.tencent.mm")`.

## Remaining limits

- BotIme must be acceptable as the default keyboard for route C.
- The Accessibility screenshot pace makes multi-part sends slower than route B.
- On the test vivo build, an ADB-written overlay app-op returned to `ignore`
  after reboot, so the bubble service did not auto-start until the overlay
  permission was granted again. A user-granted system setting must be
  rechecked independently of `BubbleSettings.enabled`.
- MediaProjection remains an option if future Android/vivo builds make
  Accessibility screenshot latency or rate limits unacceptable. Clicks would
  still use Accessibility.
