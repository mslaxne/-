param(
    [string]$AdbPath = "adb",
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [int]$LeaseWaitSeconds = 62
)

$ErrorActionPreference = "Stop"
$Package = "com.local.wxbotprobe"
$Receiver = "$Package/.DebugMemoryV2LifecycleReceiver"
$Action = "com.local.wxbotprobe.DEBUG_MEMORY_V2_LIFECYCLE"
$OutputPath = "files/debug/memory-v2-lifecycle.json"

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $result = & $AdbPath -s $Serial @AdbArgs
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
        $output = & $AdbPath -s $Serial shell run-as $Package cat $OutputPath 2>$null
        if ($LASTEXITCODE -ne 0) {
            continue
        }
        $raw = ($output -join "`n").Trim()
        if ($raw.StartsWith("{")) {
            $result = $raw | ConvertFrom-Json
            if ($result.phase -eq $ExpectedPhase -and $result.status -ne "waiting_for_lease") {
                return $result
            }
        }
    }
    throw "timed out waiting for lifecycle phase: $ExpectedPhase"
}

function Assert-Passed {
    param($Result, [string]$ExpectedStatus)
    if ($Result.status -ne $ExpectedStatus) {
        throw "expected status $ExpectedStatus, got $($Result.status)"
    }
}

$prepared = $null
$verified = $null
try {
    $services = Invoke-Adb -AdbArgs @(
        "shell", "dumpsys", "activity", "services", $Package
    )
    if ($services -match "ServiceRecord.*BotLoopService") {
        throw "stop the agent before running the process-kill lifecycle probe"
    }
    Start-ColdApp
    Send-Phase "prepare"
    $prepared = Read-Result "prepare"
    Assert-Passed $prepared "prepared"
    if ($prepared.session.state -ne "open" -or $prepared.session.next_seq -ne 2) {
        throw "prepare did not leave one open session at next_seq=2"
    }
    if ($prepared.job.state -ne "leased" -or $prepared.job.attempts -ne 1) {
        throw "prepare did not create the expected leased job"
    }

    Invoke-Adb -AdbArgs @("shell", "am", "force-stop", $Package) | Out-Null
    Start-Sleep -Milliseconds 500
    $pidAfterStop = & $AdbPath -s $Serial shell pidof $Package
    $pidAfterStop = ($pidAfterStop -join "`n").Trim()
    if ($pidAfterStop) {
        throw "package process survived force-stop: $pidAfterStop"
    }

    Write-Host "Process killed. Waiting $LeaseWaitSeconds seconds for the durable lease to expire."
    Start-Sleep -Seconds $LeaseWaitSeconds
    Invoke-Adb -AdbArgs @(
        "shell", "am", "start", "-W", "-n", "$Package/.MainActivity"
    ) | Out-Null
    Start-Sleep -Milliseconds 750
    Send-Phase "verify"
    $verified = Read-Result "verify"
    Assert-Passed $verified "passed"
    if ($verified.recovered_session_id -ne $prepared.session_id) {
        throw "recovery bound a different session"
    }
    if ($verified.appended_seq -ne 2 -or $verified.replay_status -ne "unchanged") {
        throw "post-restart sequence or idempotency check failed"
    }
    if ($verified.final.session.state -ne "closed") {
        throw "recovered session did not close cleanly"
    }
    if ($verified.final.integrity.quick_check -ne "ok") {
        throw "SQLite quick_check did not pass"
    }
    if ($verified.final.integrity.foreign_key_violations -ne 0) {
        throw "foreign key violations were found"
    }

    Write-Host "Memory V2 process-kill lifecycle passed."
    $verified | ConvertTo-Json -Depth 8
} finally {
    try {
        Send-Phase "cleanup"
        Start-Sleep -Milliseconds 750
    } catch {
        Write-Warning "Lifecycle cleanup broadcast failed: $($_.Exception.Message)"
    }
}
