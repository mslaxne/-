param(
    [string]$AdbPath = "adb",
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [int]$Port = 5038,
    [int]$DurationMinutes = 30,
    [int]$IntervalSeconds = 30,
    [string]$OutputPath = "build-artifacts-oneplus-soak.csv"
)

$ErrorActionPreference = "Stop"
$Package = "com.local.wxbotprobe"
$Deadline = (Get-Date).AddMinutes($DurationMinutes)
$Rows = [System.Collections.Generic.List[object]]::new()

function Invoke-AdbText {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $text = (& $AdbPath -P $Port -s $Serial @Arguments 2>$null) -join "`n"
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb failed: $($Arguments -join ' ')"
    }
    return $text.Trim()
}

function Count-DeviceFiles {
    param([string]$Command)
    $value = Invoke-AdbText -AllowFailure -Arguments @("shell", $Command)
    $parsed = 0
    if ([int]::TryParse($value.Trim(), [ref]$parsed)) {
        return $parsed
    }
    return -1
}

while ((Get-Date) -lt $Deadline) {
    $sampledAt = Get-Date
    $pidText = Invoke-AdbText -AllowFailure -Arguments @("shell", "pidof", $Package)
    $pidValue = $pidText.Split("`n")[0].Trim()
    $service = Invoke-AdbText -AllowFailure -Arguments @(
        "shell", "dumpsys", "activity", "services", $Package
    )
    $botLoop = $service -match "BotLoopService"
    $memory = Invoke-AdbText -AllowFailure -Arguments @(
        "shell", "dumpsys", "meminfo", $Package
    )
    $pssMatch = [regex]::Match($memory, "TOTAL PSS:\s+(\d+)")
    $pssKb = if ($pssMatch.Success) { [long]$pssMatch.Groups[1].Value } else { -1L }
    $power = Invoke-AdbText -AllowFailure -Arguments @("shell", "dumpsys", "power")
    $wakeMatch = [regex]::Match($power, "mWakefulness=(\w+)")
    $wakefulness = if ($wakeMatch.Success) { $wakeMatch.Groups[1].Value } else { "unknown" }
    $pending = Count-DeviceFiles (
        "grep -l '`"status`": `"pending`"' " +
            "/sdcard/Android/data/$Package/files/outbox/*.json 2>/dev/null | wc -l"
    )
    $retries = Count-DeviceFiles (
        "find /sdcard/Android/data/$Package/files/reply-retries " +
            "-maxdepth 1 -type f 2>/dev/null | wc -l"
    )
    $row = [pscustomobject]@{
        sampled_at = $sampledAt.ToString("yyyy-MM-dd HH:mm:ss")
        pid = $pidValue
        bot_loop = $botLoop
        pss_kb = $pssKb
        wakefulness = $wakefulness
        pending_delivery = $pending
        retry_files = $retries
    }
    $Rows.Add($row)
    $Rows | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Encoding utf8
    Write-Output (
        "{0} pid={1} loop={2} pss={3}KB wake={4} pending={5} retries={6}" -f
        $row.sampled_at,
        $row.pid,
        $row.bot_loop,
        $row.pss_kb,
        $row.wakefulness,
        $row.pending_delivery,
        $row.retry_files
    )
    $remaining = $IntervalSeconds - ((Get-Date) - $sampledAt).TotalSeconds
    if ($remaining -gt 0) {
        Start-Sleep -Milliseconds ([int]($remaining * 1000))
    }
}

Write-Output "Soak sampling complete: $OutputPath"
