# Deployment

This is a private, side-loaded Android app for one arm64 phone. It is not
intended for an app store.

## Target

- Device: vivo V2055A
- Android: 13 / API 33
- Screen reference: 1080x2400
- ABI: arm64-v8a
- App package: `com.local.wxbotprobe`

## Artifacts

- Route-C APK: `app/build/outputs/apk/routeC/release/app-routeC-release.apk`
- Route-B APK: `app/build/outputs/apk/routeB/release/app-routeB-release.apk`
- Staged APK: `dist/WxBotProbe-<versionName>.apk`
- Checksum: `dist/WxBotProbe-<versionName>.sha256`

The release build uses the existing Android debug signing key so it can update
the installed prototype in place and preserve app data. This is appropriate for
side-loading only. Keep `~/.android/debug.keystore` safe; losing it prevents
in-place updates.

## Build and install

From PowerShell:

```powershell
.\deploy.ps1 -Flavor routeC
```

To install an already-built release:

```powershell
.\deploy.ps1 -Flavor routeC -SkipBuild
```

Use `-Flavor routeB` for the rollback build containing Shizuku.

vivo may show an unknown-source safety screen. Select the acknowledgement and
continue installation manually.

The deploy script runs the unit-test and lint gates before installing, verifies
the installed version, restores the accessibility service that Android removes
during a package update, and refreshes Shizuku authorization for route B.

Route C always skips Shizuku refresh. The explicit switch is still accepted for
clarity:

```powershell
.\deploy.ps1 -Flavor routeC -SkipShizukuRefresh
```

The default `routeC` APK has no Shizuku provider, AIDL or API dependency.
`routeB` is a separate APK containing the fallback implementation.

## First-time phone setup

### Route C: default runtime target

1. Install and open WxBotProbe; allow notifications.
2. Add the app to the battery-optimization allowlist and allow vivo
   auto-start/background activity.
3. Enable `Handora 1` in Accessibility. Route C uses it for screenshots,
   gestures and vivo background keepalive.
4. Enable `Handora 1 输入法` and set BotIme as the system default keyboard.
5. Stop the agent, then enable `路线 C 无障碍传输` in Settings.
6. Configure the DeepSeek API Key in the app password dialog.
7. Confirm the WeChat chat is in text-input mode and BotIme is still default.
8. Run the test reply and two-part dry-run with auto-send off.

Do not leave Baidu selected while claiming route C is ready. The deployment
panel reports `BotIme 已启用` and `BotIme 默认` as separate checks. Route C
allows the read loop to start once BotIme is enabled and default, but the sender
still requires BotIme to receive an input connection for `com.tencent.mm`.

### Route B: fallback

1. Install Shizuku 13.6.0 or later.
2. Enable Developer options and Wireless debugging.
3. In Shizuku, use “通过无线调试启动”, complete pairing and start the server.
4. Disable `路线 C 无障碍传输` while the agent is stopped.
5. In WxBotProbe, tap `连接 / 授权 Shizuku`, then choose “始终允许”.
6. Run the route-B dry-run. Delivery temporarily selects BotIme and restores
   the previous keyboard afterward.

## Acceptance before enabling auto-send

1. Deployment status shows every route-specific core check ready.
2. `生成测试回复（不发）` writes a reply to `files/replies/`.
3. `测试输入链路（不发）` reports success and leaves no draft.
4. Start the read loop and confirm a real baseline.
5. Only then enable `生成回复后自动发送`.

## Reboot and update behavior

After a phone reboot, route C should retain its selected route and BotIme
default. Check Accessibility, bubble and agent state on the home panel.
Route B still requires Shizuku to be started again through wireless debugging.
WxBotProbe data and its encrypted API key remain installed in both cases.

The Shizuku UserService version is derived from `versionCode`, so bump
`versionCode` for every release that changes the privileged service. The deploy
script also refreshes Shizuku authorization when its app-management page is
available.

If the script cannot access Shizuku's UI, refresh it manually:

1. Open Shizuku.
2. Open its authorized-app management page.
3. Toggle WxBotProbe off and on.
4. Reopen WxBotProbe.

## Runtime files

All diagnostic files are in the app-specific external directory:

```text
Android/data/com.local.wxbotprobe/files/
  captures/        MediaProjection captures
  transport-captures/
  ocr/
  screens/
  monitor/
  inbox/
  replies/
  logs/
```

The API key is stored as AES-GCM ciphertext in Android Keystore-backed
preferences. It is never part of an APK or repository file.

## Troubleshooting

- Loop stops polling but notification remains: check battery allowlist and
  Accessibility keepalive. The process being in `/frozen` means vivo suspended
  it.
- Shizuku reports not running: start it again from its app.
- Shizuku user service never connects: toggle authorization as described above.
- Route C says BotIme is enabled but not default: open the system input-method
  picker and select `Handora 1 输入法`; the App will not switch it.
- Send button not found: verify the chat is in text-input mode, BotIme is the
  system default, and the app remains on the 1080x2400 reference device.
