param(
    [string]$AdbPath = "adb",
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [int]$AdbServerPort = 5038,
    [long]$ReserveBytes = 268435456,
    [int]$WatchdogSeconds = 90
)

$ErrorActionPreference = "Stop"
$Package = "com.local.wxbotprobe"
$Receiver = "$Package/.DebugMemoryV2CoordinatorLifecycleReceiver"
$Action = "com.local.wxbotprobe.DEBUG_MEMORY_V2_COORDINATOR_LIFECYCLE"
$OutputPath = "files/debug/memory-v2-coordinator-lifecycle.json"
$PressureFile = "/data/local/tmp/wxbot-handora-pressure.bin"
$AppFillPath = "files/pressure/physical-final.bin"

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $result = & $AdbPath -P $AdbServerPort -s $Serial @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($AdbArgs -join ' ')"
    }
    return ($result -join "`n").Trim()
}

function Get-AvailableBytes {
    $line = & $AdbPath -P $AdbServerPort -s $Serial shell df -k /data |
        Select-Object -Last 1
    $parts = ($line -split '\s+') | Where-Object { $_ }
    if ($parts.Count -lt 4) {
        throw "could not parse df output: $line"
    }
    return [long]$parts[3] * 1024L
}

function Remove-PressureFile {
    & $AdbPath -P $AdbServerPort -s $Serial shell rm -f $PressureFile 2>$null
    & $AdbPath -P $AdbServerPort -s $Serial shell run-as $Package rm -f $AppFillPath 2>$null
}

$result = $null
try {
    $services = Invoke-Adb -AdbArgs @(
        "shell", "dumpsys", "activity", "services", $Package
    )
    if ($services -match "ServiceRecord.*BotLoopService") {
        throw "stop the agent before running the physical-disk probe"
    }
    $freeBefore = Get-AvailableBytes
    if ($freeBefore -lt 2147483648) {
        throw "device already has less than 2 GB available: $freeBefore"
    }
    Invoke-Adb -AdbArgs @(
        "shell",
        "nohup sh -c 'sleep $WatchdogSeconds; rm -f $PressureFile; " +
            "run-as $Package rm -f $AppFillPath' " +
            ">/dev/null 2>&1 &"
    ) | Out-Null
    $fillBytes = $freeBefore - $ReserveBytes
    if ($fillBytes -le 0) {
        throw "invalid pressure allocation: $fillBytes"
    }
    Invoke-Adb -AdbArgs @(
        "shell", "fallocate", "-l", $fillBytes.ToString(), $PressureFile
    ) | Out-Null
    $freeLow = Get-AvailableBytes
    Write-Host "Pressure file active. free_before=$freeBefore free_low=$freeLow"

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
        "--es", "phase", "physical_disk_pressure"
    ) | Out-Null

    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        Start-Sleep -Milliseconds 500
        $output = & $AdbPath -P $AdbServerPort -s $Serial shell run-as $Package cat $OutputPath 2>$null
        if ($LASTEXITCODE -ne 0) {
            continue
        }
        $raw = ($output -join "`n").Trim()
        if ($raw.StartsWith("{")) {
            $candidate = $raw | ConvertFrom-Json
            if ($candidate.phase -eq "physical_disk_pressure") {
                $result = $candidate
                break
            }
        }
    }
    if ($null -eq $result) {
        throw "timed out waiting for the physical-disk probe"
    }
    if ($result.status -ne "passed") {
        throw "physical-disk probe failed: $($result.error)"
    }
    if ($result.failure_code -ne "STORAGE_UNAVAILABLE") {
        throw "physical-disk probe did not report STORAGE_UNAVAILABLE"
    }
    if ($result.after_failure.session.next_seq -ne
        $result.session_before_failure.next_seq) {
        throw "physical-disk write changed the session sequence"
    }
    if ($result.after_failure.message_count -ne
        ($result.successful_pressure_messages + 1)) {
        throw "physical-disk write left a partial message"
    }
    if ($result.after_failure.jobs.state -ne "pending") {
        throw "physical-disk write lost the pending job"
    }
    if ($result.integrity.quick_check -ne "ok" -or
        $result.integrity.foreign_key_violations -ne 0) {
        throw "physical-disk database integrity check failed"
    }
    if (-not $result.database_deleted) {
        throw "isolated physical-pressure database was not deleted"
    }
    Write-Host "Memory V2 physical disk pressure passed."
    $result | ConvertTo-Json -Depth 12
} finally {
    Remove-PressureFile
    Start-Sleep -Seconds 1
    try {
        $freeRestored = Get-AvailableBytes
        Write-Host "Pressure file removed. free_restored=$freeRestored"
    } catch {
        Write-Warning "Could not read restored free space: $($_.Exception.Message)"
    }
}
