param(
    [string]$AvdName = ""
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$env:ANDROID_HOME = "D:\Projects\AndroidSDK"
$env:JAVA_HOME = "D:\Projects\AndroidSDK\jdk-17"
$ADB = "D:\Projects\AndroidSDK\platform-tools\adb.exe"
$EMU = "D:\Projects\AndroidSDK\emulator\emulator.exe"

# Проверяем, запущен ли уже эмулятор
$devices = & $ADB devices | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of" }

if ($devices) {
    Write-Host "Обнаружено уже запущенное устройство/эмулятор Android TV." -ForegroundColor Green
    exit 0
}

# Определяем AVD для запуска
$availableAvds = & $EMU -list-avds
if (-not $availableAvds) {
    Write-Error "Не найдено ни одного настроенного виртуального устройства (AVD) в Android SDK."
    exit 1
}

$targetAvd = $AvdName
if (-not $targetAvd) {
    if ($availableAvds -contains "ATV_11") {
        $targetAvd = "ATV_11"
    } elseif ($availableAvds -contains "ATV_9") {
        $targetAvd = "ATV_9"
    } else {
        $targetAvd = $availableAvds[0]
    }
} elseif ($availableAvds -notcontains $targetAvd) {
    Write-Warning "Указанный AVD '$targetAvd' не найден. Доступные: $($availableAvds -join ', ')"
    $targetAvd = $availableAvds[0]
}

Write-Host "Запуск эмулятора Android TV ($targetAvd)..." -ForegroundColor Cyan
Start-Process $EMU -ArgumentList "-avd", $targetAvd, "-netdelay", "none", "-netspeed", "full"

Write-Host "Ожидание готовности ADB..." -ForegroundColor Yellow
& $ADB wait-for-device

Write-Host "Ожидание полной загрузки системы Android TV..." -ForegroundColor Yellow
while ($true) {
    $boot = (& $ADB shell getprop sys.boot_completed 2>$null)
    if ($boot -and $boot.Trim() -eq "1") { break }
    Start-Sleep -Seconds 2
}

Write-Host "Эмулятор Android TV ($targetAvd) готов к работе!" -ForegroundColor Green