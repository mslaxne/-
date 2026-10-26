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
    throw "stop the agent before running the restore-fence probe"
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
    "--es", "phase", "restore_fence"
) | Out-Null

$result = $null
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    Start-Sleep -Milliseconds 500
    $output = & $AdbPath -P $AdbServerPort -s $Serial shell run-as $Package cat $OutputPath 2>$null
    if ($LASTEXITCODE -ne 0) {
        continue
    }
    $raw = ($output -join "`n").Trim()
    if ($raw.StartsWith("{")) {
        $candidate = $raw | ConvertFrom-Json
        if ($candidate.phase -eq "restore_fence") {
            $result = $candidate
            break
        }
    }
}
if ($null -eq $result) {
    throw "timed out waiting for the restore-fence probe"
}
if ($result.status -ne "passed") {
    throw "restore-fence probe failed: $($result.error)"
}
if ($result.bound_chat_id -ne $null) {
    throw "restore fence allowed a foreign scope to bind"
}
if ($result.message_count -ne 1 -or $result.session_count -ne 1) {
    throw "restore fence allowed a message or session to be created"
}
if ($result.after.next_seq -ne $result.before.next_seq) {
    throw "restore fence changed the session sequence"
}
if ($result.integrity.quick_check -ne "ok" -or
    $result.integrity.foreign_key_violations -ne 0) {
    throw "restore-fence database integrity check failed"
}
if (-not $result.database_deleted) {
    throw "isolated restore-fence database was not deleted"
}

Write-Host "Memory V2 backup/restore fence passed."
$result | ConvertTo-Json -Depth 12
