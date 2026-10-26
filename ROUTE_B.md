# Route B: Shizuku thin shell

Status: route B was the first functional port and is now the retained fallback.
Route C is the default runtime path. Route B is built separately:

```powershell
.\deploy.ps1 -Flavor routeB
```

The default `routeC` APK does not contain the Shizuku provider, AIDL or API
dependency.

The screenshot and tap transport now runs entirely on the phone:

```text
WeChat
  -> Shizuku UserService (shell UID 2000)
  -> /system/bin/screencap or /system/bin/input tap
  -> app-owned screenshot file

reply text
  -> custom InputMethodService
  -> InputConnection.commitText
```

## Implemented

- Shizuku API/provider 13.1.5, tested against Shizuku 13.6.0.
- AIDL boundary exposes only:
  - `screenshot(path)`
  - `tap(x, y)`
  - `uid()`
- The privileged service is non-daemon and tied to the app process.
- UserService `version` is derived from the app `versionCode`; bump the app
  version whenever privileged service code changes.
- If binding remains at "正在连接" after an APK update, toggle this app's
  authorization off and on in Shizuku's app-management screen, then reopen the
  bot. This was required once on the test phone even though the permission
  switch still appeared enabled.
- The bot process must be exempt from battery optimization. Without the device
  idle whitelist, vivo reported `isFrozen=true` even while the data-sync
  foreground service and notification remained alive.
- On this vivo build, enabling this app's AccessibilityService is also required
  as a keepalive. Shizuku still performs screenshots and taps; accessibility is
  not used for those actions. Enabling it moved the process from `/frozen` to
  `l-foreground` immediately.
- Shizuku authorization and UserService connection are exposed in the app UI.
- Screenshot output is written to the app external-files directory, avoiding
  large Binder payloads.
- Existing MediaProjection and AccessibilityService paths remain available as
  fallbacks.

## Verified on the V2055A

- Shizuku UserService connected with `UID=2000`.
- Shizuku screenshot: 1080x2400, 0.047% black pixels.
- Compared with contemporaneous ADB screenshot: 99.992% identical pixels,
  mean absolute error 0.005.
- Shizuku `input tap 538 2320` focused the WeChat input
  (`mInputShown=true`).
- Custom IME wrote `这是测试` into `com.tencent.mm`; no send tap was performed.
- RapidOCR Android won the OCR comparison; ML Kit was removed after it
  duplicated characters in a known test message. See `OCR_BENCHMARK.md`.
- A foreground read loop now performs screenshot, OCR, baseline tracking and
  three-second burst collection without model calls. See `BOT_LOOP.md`.
- Loop bursts now generate a reply and write it to `replies/` without sending.
  See `MODEL_PORT.md`.
- Explicit and auto-send paths now use BotIme plus Shizuku. Dry-run and one live
  self-chat send passed. See `SEND_PIPELINE.md`.

## Remaining platform work

- Add a foreground bot service so the loop survives while WeChat is foreground.
- Add a clear Shizuku stopped/rebooting state and one-tap route to the Shizuku app.
- Add file logging and export for field diagnosis.
- Recheck vivo auto-start and battery whitelist behavior with the final bot
  service rather than the prototype activity.

## Next implementation slice

Port the read-screen logic from `ARCHITECTURE.md`:

1. OCR abstraction and Android OCR engine selection.
2. Row filtering using the existing position, ink and noise rules.
3. Bubble merging using the measured 90/25 px and left-margin rules.
4. Side attribution and image-region detection.
5. Kotlin tests generated from the existing Python fixtures.

Do not move to model calls until these rules have real-screenshot regressions.

Status: the phone-independent rules from steps 2 to 5 are ported and covered by
26 Kotlin tests. OCR engine selection and a real `ScreenSnapshot` are complete.
See `READ_SCREEN_PORT.md` and `OCR_BENCHMARK.md`.
