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

$services = Invoke-Adb -AdbArgs @(
    "shell", "dumpsys", "activity", "services", $Package
)
if ($services -match "ServiceRecord.*BotLoopService") {
    throw "stop the agent before running the storage-pressure probe"
}

Invoke-Adb -AdbArgs @("shell", "am", "force-stop", $Package) | Out-Null
Start-Sleep -Milliseconds 500
Invoke-Adb -AdbArgs @(
    "shell", "am", "start", "-W", "-n", "$Package/.MainActivity"
) | Out-Null
Start-Sleep -Milliseconds 750
Invoke-Adb -AdbArgs @(
    "shell", "am", "broadcast",
    "-a", $Action,
    "-n", $Receiver,
    "--es", "phase", "storage_pressure"
) | Out-Null

$result = $null
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    Start-Sleep -Milliseconds 500
    $output = & $AdbPath -P $AdbServerPort -s $Serial shell run-as $Package cat $OutputPath 2>$null
    if ($LASTEXITCODE -ne 0) {
        continue
    }
    $raw = ($output -join "`n").Trim()
    if ($raw.StartsWith("{")) {
        $candidate = $raw | ConvertFrom-Json
        if ($candidate.phase -eq "storage_pressure") {
            $result = $candidate
            break
        }
    }
}
if ($null -eq $result) {
    throw "timed out waiting for the storage-pressure probe"
}
if ($result.status -ne "passed") {
    throw "storage-pressure probe failed: $($result.error)"
}
if ($result.append_error_code -ne "RESOURCE_CAPACITY_EXHAUSTED") {
    throw "storage-pressure probe did not exhaust the message capacity"
}
if ($result.after_failure.session.next_seq -ne
    $result.session_before_failure.next_seq) {
    throw "storage-pressure write changed the session sequence"
}
if ($result.after_failure.message_count -ne
    ($result.successful_pressure_messages + 1)) {
    throw "storage-pressure write left a partial message"
}
if ($result.job_before.state -ne "pending" -or
    $result.after_failure.jobs.state -ne "pending") {
    throw "storage-pressure write lost or changed the pending job"
}
if ($result.integrity.quick_check -ne "ok" -or
    $result.integrity.foreign_key_violations -ne 0) {
    throw "storage-pressure database integrity check failed"
}
if (-not $result.database_deleted) {
    throw "isolated storage-pressure database was not deleted"
}

Write-Host "Memory V2 capacity-pressure rollback passed."
$result | ConvertTo-Json -Depth 12
