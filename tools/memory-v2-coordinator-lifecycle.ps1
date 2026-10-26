param(
    [string]$AdbPath = "adb",
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [int]$AdbServerPort = 5038
)

$ErrorActionPreference = "Stop"
$Package = "com.local.wxbotprobe"
$Receiver = "$Package/.DebugMemoryV2CoordinatorLifecycleReceiver"
$Action = "com.local.wxbotprobe.DEBUG_MEMORY_V2_COORDINATOR_LIFECYCLE"
$OutputPath = "files/debug/memory-v2-coordinator-lifecycle.json"

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $result = & $AdbPath -P $AdbServerPort -s $Serial @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($AdbArgs -join ' ')"
    }
    return ($result -join "`n").Trim()
}

function Send-Phase {
    param([string]$Phase)
    Invoke-Adb -AdbArgs @(
        "shell", "am", "broadcast",
        "-a", $Action,
        "-n", $Receiver,
        "--es", "phase", $Phase
    ) | Out-Null
}

function Start-ColdApp {
    Invoke-Adb -AdbArgs @("shell", "am", "force-stop", $Package) | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-Adb -AdbArgs @(
        "shell", "am", "start", "-W", "-n", "$Package/.MainActivity"
    ) | Out-Null
    Start-Sleep -Milliseconds 750
}

function Read-Result {
    param([string]$ExpectedPhase)
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Milliseconds 500
        $output = & $AdbPath -P $AdbServerPort -s $Serial shell run-as $Package cat $OutputPath 2>$null
        if ($LASTEXITCODE -ne 0) {
            continue
        }
        $raw = ($output -join "`n").Trim()
        if ($raw.StartsWith("{")) {
            $result = $raw | ConvertFrom-Json
            if ($result.phase -eq $ExpectedPhase) {
                return $result
            }
        }
    }
    throw "timed out waiting for coordinator lifecycle phase: $ExpectedPhase"
}

function Assert-Passed {
    param($Result, [string]$ExpectedStatus)
    if ($Result.status -ne $ExpectedStatus) {
        throw "expected status $ExpectedStatus, got $($Result.status): $($Result.error)"
    }
}

$prepared = $null
$verified = $null
try {
    $services = Invoke-Adb -AdbArgs @(
        "shell", "dumpsys", "activity", "services", $Package
    )
    if ($services -match "ServiceRecord.*BotLoopService") {
        throw "stop the agent before running the coordinator lifecycle probe"
    }
    Start-ColdApp
    Send-Phase "prepare"
    $prepared = Read-Result "prepare"
    Assert-Passed $prepared "prepared"
    if ($prepared.session.state -ne "open" -or $prepared.session.next_seq -ne 3) {
        throw "prepare did not leave one coordinator-owned open session at next_seq=3"
    }
    if ($prepared.message_count -ne 2) {
        throw "prepare did not persist two coordinator messages"
    }

    Invoke-Adb -AdbArgs @("shell", "am", "force-stop", $Package) | Out-Null
    Start-Sleep -Milliseconds 500
    $pidAfterStop = & $AdbPath -P $AdbServerPort -s $Serial shell pidof $Package
    $pidAfterStopText = ($pidAfterStop -join "`n").Trim()
    if ($pidAfterStopText) {
        throw "package process survived force-stop: $pidAfterStopText"
    }

    Invoke-Adb -AdbArgs @(
        "shell", "am", "start", "-W", "-n", "$Package/.MainActivity"
    ) | Out-Null
    Start-Sleep -Milliseconds 750
    Send-Phase "verify"
    $verified = Read-Result "verify"
    Assert-Passed $verified "passed"
    if ($verified.recovered_session_id -ne $prepared.session_id) {
        throw "coordinator recovery bound a different session"
    }
    if ($verified.after_open.next_seq -ne 3) {
        throw "recovered coordinator next_seq was not 3"
    }
    if ($verified.final_session.state -ne "closed" -or $verified.final_session.next_seq -ne 4) {
        throw "recovered coordinator session did not close at next_seq=4"
    }
    if ($verified.session_count -ne 1 -or $verified.message_count -ne 3) {
        throw "coordinator recovery duplicated sessions or lost a message"
    }
    if ($verified.integrity.quick_check -ne "ok" -or
        $verified.integrity.foreign_key_violations -ne 0) {
        throw "coordinator database integrity check failed"
    }
    if (-not ($verified.logs -match "memory v2 session recovered")) {
        throw "coordinator recovery log was not observed"
    }

    Write-Host "Memory V2 coordinator-owned process-kill lifecycle passed."
    $verified | ConvertTo-Json -Depth 10
} finally {
    try {
        Send-Phase "cleanup"
        Start-Sleep -Milliseconds 750
    } catch {
        Write-Warning "Coordinator lifecycle cleanup broadcast failed: $($_.Exception.Message)"
    }
}
