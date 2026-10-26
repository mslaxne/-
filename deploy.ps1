param(
    [switch]$SkipBuild,
    [switch]$SkipShizukuRefresh,
    [ValidateSet("routeC", "routeB")]
    [string]$Flavor = "routeC"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = Join-Path $projectRoot ".tooling\jdk17"
$gradle = Join-Path $projectRoot ".tooling\gradle-8.10.2\bin\gradle.bat"
$androidSdk = Join-Path $projectRoot ".tooling\android-sdk"
$apksigner = Get-ChildItem -LiteralPath (Join-Path $androidSdk "build-tools") `
    -Recurse -Filter "apksigner.bat" |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$profileAndroidHome = Join-Path $env:USERPROFILE ".android"
$androidUserHome = if (
    Test-Path -LiteralPath (Join-Path $profileAndroidHome "debug.keystore")
) {
    $profileAndroidHome
} else {
    Join-Path $projectRoot ".android"
}
$gradleUserHome = Join-Path $projectRoot ".gradle"
$adb = if ($env:ADB) { $env:ADB } else { "adb" }

function Test-AccessibilityBound(
    [string]$dumpText,
    [string]$serviceLabel = "Handora 1"
) {
    if ([string]::IsNullOrWhiteSpace($dumpText)) {
        return $false
    }
    $boundServices = $dumpText -split "`r?`n" |
        Where-Object { $_ -match "^\s*Bound services:" } |
        Out-String
    return $boundServices -match (
        "Service\[label=" + [regex]::Escape($serviceLabel) + "(?:,|\])"
    )
}

function Test-SigningCompatible(
    [string]$builtDigest,
    [string]$installedDigest
) {
    if ([string]::IsNullOrWhiteSpace($installedDigest)) {
        return $true
    }
    return $builtDigest -eq $installedDigest
}

function Get-ApkSignerDigest([string]$apkPath) {
    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "APK not found for signing check: $apkPath"
    }
    if ([string]::IsNullOrWhiteSpace($apksigner) -or
        -not (Test-Path -LiteralPath $apksigner)) {
        throw "apksigner was not found in the configured Android SDK."
    }
    $output = & $apksigner verify --print-certs $apkPath
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read APK signing certificate: $apkPath"
    }
    $line = $output |
        Where-Object { $_ -match "certificate SHA-256 digest:\s*(\S+)" } |
        Select-Object -First 1
    if ($null -eq $line -or $line -notmatch "certificate SHA-256 digest:\s*(\S+)") {
        throw "APK signing certificate digest was not reported: $apkPath"
    }
    return $Matches[1].ToLowerInvariant()
}

function Get-InstalledSignerDigest([string]$serial) {
    $paths = @(& $adb -s $serial shell pm path "com.local.wxbotprobe")
    $remote = $paths |
        Where-Object { $_ -match "^package:" } |
        Select-Object -First 1
    if ($null -eq $remote) {
        return $null
    }
    $temporary = Join-Path $env:TEMP "handora-installed-$PID.apk"
    try {
        & $adb -s $serial pull ($remote -replace "^package:", "") $temporary |
            Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to pull the installed APK for signing verification."
        }
        return Get-ApkSignerDigest $temporary
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

function Get-UiSnapshot([string]$serial) {
    & $adb -s $serial shell uiautomator dump /sdcard/wxbot-deploy-ui.xml | Out-Null
    return [xml]((& $adb -s $serial shell cat /sdcard/wxbot-deploy-ui.xml | Out-String))
}

function Get-BoundsCenter([string]$bounds) {
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Unexpected UI bounds: $bounds"
    }
    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
    return $x, $y
}

function Tap-UiNode($serial, $node) {
    $center = Get-BoundsCenter $node.bounds
    & $adb -s $serial shell input tap ([int]$center[0]) ([int]$center[1])
}

function Get-ClickableAncestor($node) {
    $current = $node
    while ($null -ne $current -and $current.Name -eq "node") {
        if ($current.clickable -eq "true") {
            return $current
        }
        $current = $current.ParentNode
    }
    throw "No clickable ancestor found for UI node."
}

function Refresh-ShizukuAuthorization($serial) {
    $package = "moe.shizuku.privileged.api"
    $installed = (& $adb -s $serial shell pm list packages $package | Out-String)
    if ($installed -notmatch "package:$([regex]::Escape($package))") {
        Write-Host "Shizuku:   not installed; authorization refresh skipped"
        return
    }

    & $adb -s $serial shell am start -W -n "$package/moe.shizuku.manager.MainActivity" | Out-Null
    Start-Sleep -Seconds 1
    $appsUi = Get-UiSnapshot $serial
    $appNode = $appsUi.SelectSingleNode(
        "//node[@text='com.local.wxbotprobe' or @text='微信机器人原型']"
    )
    if ($null -eq $appNode) {
        $authorizedNode = $appsUi.SelectSingleNode(
            "//node[contains(@text,'已授权') and contains(@text,'应用')]"
        )
        if ($null -eq $authorizedNode) {
            Write-Host "Shizuku:   no authorized app to refresh"
            return
        }

        Tap-UiNode $serial (Get-ClickableAncestor $authorizedNode)
        Start-Sleep -Milliseconds 700
        $appsUi = Get-UiSnapshot $serial
        $appNode = $appsUi.SelectSingleNode(
            "//node[@text='com.local.wxbotprobe' or @text='微信机器人原型']"
        )
    }
    if ($null -eq $appNode) {
        Write-Host "Shizuku:   app is not in the authorized list; refresh skipped"
        return
    }

    $row = Get-ClickableAncestor $appNode
    $switch = $row.SelectSingleNode(".//node[@class='android.widget.Switch']")
    if ($null -eq $switch) {
        throw "Shizuku authorization switch was not found."
    }
    if ($switch.checked -ne "true") {
        throw "Shizuku authorization switch is unexpectedly off."
    }

    Tap-UiNode $serial $switch
    Start-Sleep -Milliseconds 700
    Tap-UiNode $serial $switch
    Start-Sleep -Seconds 1
    $verifiedUi = Get-UiSnapshot $serial
    $verifiedSwitch = $verifiedUi.SelectSingleNode(
        "//node[@text='com.local.wxbotprobe' or @text='微信机器人原型']" +
        "/ancestor::node[@clickable='true'][1]//node[@class='android.widget.Switch']"
    )
    if ($null -eq $verifiedSwitch -or $verifiedSwitch.checked -ne "true") {
        throw "Shizuku authorization refresh did not complete."
    }
    Write-Host "Shizuku:   authorization refreshed"
}

if (-not $SkipBuild) {
    if (-not (Test-Path -LiteralPath $gradle)) {
        throw "Gradle tooling is missing. Run .tooling\download-tooling.ps1 first."
    }
    $env:JAVA_HOME = $jdk
    $env:ANDROID_HOME = $androidSdk
    $env:ANDROID_SDK_ROOT = $androidSdk
    $env:ANDROID_USER_HOME = $androidUserHome
    $env:GRADLE_USER_HOME = $gradleUserHome
    Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $androidUserHome | Out-Null
    New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null
    $flavorCamel = if ($Flavor -eq "routeB") { "RouteB" } else { "RouteC" }
    & $gradle --no-daemon `
        ":app:assemble${flavorCamel}Release" `
        ":app:test${flavorCamel}DebugUnitTest" `
        ":app:lint${flavorCamel}Debug"
    if ($LASTEXITCODE -ne 0) {
        throw "Release build, tests, or lint failed."
    }

    $testFiles = Get-ChildItem -LiteralPath (
        Join-Path $projectRoot "app\build\test-results\test${flavorCamel}DebugUnitTest"
    ) -Filter "TEST-*.xml"
    $testCount = 0
    $testFailures = 0
    foreach ($testFile in $testFiles) {
        $suite = [xml](Get-Content -Raw -LiteralPath $testFile.FullName)
        $testCount += [int]$suite.testsuite.tests
        $testFailures += [int]$suite.testsuite.failures + [int]$suite.testsuite.errors
    }
    if ($testCount -eq 0 -or $testFailures -ne 0) {
        throw "Unit test gate failed: tests=$testCount failures=$testFailures"
    }

    $lintReport = Join-Path $projectRoot (
        "app\build\reports\lint-results-$Flavor" + "Debug.txt"
    )
    if (-not (Test-Path -LiteralPath $lintReport)) {
        throw "Lint report is missing."
    }
    if ((Get-Content -Raw -LiteralPath $lintReport).Trim() -ne "No issues found.") {
        throw "Lint found issues. Review $lintReport"
    }
    Write-Host "Tests:     $testCount passed"
    Write-Host "Lint:      No issues found"
}

$apk = Join-Path $projectRoot (
    "app\build\outputs\apk\$Flavor\release\app-$Flavor-release.apk"
)
$metadataPath = Join-Path $projectRoot (
    "app\build\outputs\apk\$Flavor\release\output-metadata.json"
)
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Release APK not found: $apk"
}
if (-not (Test-Path -LiteralPath $metadataPath)) {
    throw "APK metadata not found: $metadataPath"
}
if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB not found: $adb"
}

$metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
$versionCode = [int]$metadata.elements[0].versionCode
$versionName = [string]$metadata.elements[0].versionName

$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
if ($devices.Count -ne 1) {
    throw "Expected exactly one authorized Android device, found $($devices.Count)."
}
$serial = ($devices[0] -split "\s+")[0]

$accessibilityComponent = "com.local.wxbotprobe/com.local.wxbotprobe.ProbeAccessibilityService"
$builtSigner = Get-ApkSignerDigest $apk
$installedSigner = Get-InstalledSignerDigest $serial
if (-not (Test-SigningCompatible $builtSigner $installedSigner)) {
    throw (
        "Built APK signing certificate does not match the installed package. " +
        "Rebuild with the existing debug keystore; do not uninstall or clear app data."
    )
}
if ($installedSigner) {
    Write-Host "Signing:   existing package certificate matched"
} else {
    Write-Host "Signing:   fresh install; no existing certificate to compare"
}
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "Install failed. Confirm the vivo unknown-source installation screen."
}

$installed = (& $adb -s $serial shell dumpsys package "com.local.wxbotprobe" | Out-String)
if ($installed -notmatch "versionCode=$versionCode\b" -or
    $installed -notmatch "versionName=$([regex]::Escape($versionName))\b") {
    throw "Installed version does not match APK metadata ($versionName / $versionCode)."
}

& $adb -s $serial shell am force-stop "com.local.wxbotprobe"
$shouldRefreshShizuku = $Flavor -eq "routeB" -and -not $SkipShizukuRefresh
if ($shouldRefreshShizuku) {
    Refresh-ShizukuAuthorization $serial
} else {
    Write-Host "Shizuku:   authorization refresh skipped"
}

$accessibilityNow = (& $adb -s $serial shell settings get secure enabled_accessibility_services | Out-String).Trim()
$enabledServices = @(
    $accessibilityNow -split ":" | Where-Object { $_ -and $_ -ne "null" }
)
if ($enabledServices -notcontains $accessibilityComponent) {
    $enabledServices += $accessibilityComponent
}
for ($attempt = 1; $attempt -le 5; $attempt++) {
    & $adb -s $serial shell settings put secure enabled_accessibility_services ($enabledServices -join ":")
    & $adb -s $serial shell settings put secure accessibility_enabled 1
    Start-Sleep -Seconds 2
    $verifiedServices = (& $adb -s $serial shell settings get secure enabled_accessibility_services | Out-String).Trim()
    if (($verifiedServices -split ":") -contains $accessibilityComponent) {
        Start-Sleep -Seconds 2
        $reverifiedServices = (& $adb -s $serial shell settings get secure enabled_accessibility_services | Out-String).Trim()
        if (($reverifiedServices -split ":") -contains $accessibilityComponent) {
            Write-Host "Accessibility: restored after package update"
            break
        }
    }
    if ($attempt -eq 5) {
        throw "Accessibility service was not restored after package update."
    }
}

$bound = $false
for ($attempt = 1; $attempt -le 5; $attempt++) {
    $accessibilityDump = (
        & $adb -s $serial shell dumpsys accessibility | Out-String
    )
    if (Test-AccessibilityBound $accessibilityDump) {
        $bound = $true
        Write-Host "Accessibility: bound after package update"
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $bound) {
    throw "Accessibility service is enabled but not bound after package update."
}

& $adb -s $serial shell am start -W -n "com.local.wxbotprobe/.MainActivity" | Out-Null
Start-Sleep -Seconds 2

$dist = Join-Path $projectRoot "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$artifactName = "WxBotProbe-$versionName.apk"
$stagedApk = Join-Path $dist $artifactName
Copy-Item -LiteralPath $apk -Destination $stagedApk -Force

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stagedApk).Hash
"$hash *$artifactName" |
    Set-Content -LiteralPath (Join-Path $dist "WxBotProbe-$versionName.sha256") -Encoding ascii

Write-Host "Device:    $serial"
Write-Host "Flavor:    $Flavor"
Write-Host "Installed: $versionName ($versionCode)"
Write-Host "Staged:    $stagedApk"
Write-Host "SHA-256:   $hash"
