param(
    [string]$AvdName = ""
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$env:ANDROID_HOME = "D:\Projects\AndroidSDK"
$env:ANDROID_SDK_ROOT = "D:\Projects\AndroidSDK"
$env:JAVA_HOME = "D:\Projects\AndroidSDK\jdk-17"
$ADB = "D:\Projects\AndroidSDK\platform-tools\adb.exe"
$EMU = "D:\Projects\AndroidSDK\emulator\emulator.exe"

# 1. Проверяем, запущен ли уже эмулятор
$devices = & $ADB devices | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of" }
if ($devices) {
    Write-Host "Обнаружено уже запущенное устройство/эмулятор Android TV." -ForegroundColor Green
    exit 0
}

# 2. Поиск установленных образов и валидация доступных AVD
$availableAvds = & $EMU -list-avds
if (-not $availableAvds) {
    Write-Error "В Android SDK не найдено ни одного виртуального устройства (AVD)."
    exit 1
}

$validAvds = @()
foreach ($name in $availableAvds) {
    $cfg = "$env:USERPROFILE\.android\avd\$name.avd\config.ini"
    if (Test-Path $cfg) {
        $dir = (Get-Content $cfg | Where-Object { $_ -match "^image\.sysdir\.1\s*=" } | ForEach-Object { ($_ -split "=")[1].Trim() })
        if ($dir -and (Test-Path "$env:ANDROID_HOME\$dir")) {
            $validAvds += $name
        }
    }
}

if (-not $validAvds) {
    Write-Error "Ни для одного AVD не найден установленный образ системы в $env:ANDROID_HOME\system-images."
    exit 1
}

# 3. Выбор целевого AVD
$targetAvd = $AvdName
if ($targetAvd) {
    if ($validAvds -notcontains $targetAvd) {
        Write-Warning "AVD '$targetAvd' не имеет установленного образа системы. Доступные рабочие AVD: $($validAvds -join ', ')"
        $targetAvd = $validAvds[0]
    }
} else {
    # По умолчанию берем первый рабочий AVD (например, ATV_9)
    if ($validAvds -contains "ATV_9") {
        $targetAvd = "ATV_9"
    } else {
        $targetAvd = $validAvds[0]
    }
}

Write-Host "Запуск эмулятора Android TV ($targetAvd)..." -ForegroundColor Cyan
$emuProc = Start-Process $EMU -ArgumentList "-avd", $targetAvd, "-netdelay", "none", "-netspeed", "full" -PassThru

# Проверяем, что процесс не упал сразу
Start-Sleep -Seconds 3
if ($emuProc.HasExited) {
    Write-Error "Процесс эмулятора аварийно завершился сразу после старта (код: $($emuProc.ExitCode))."
    exit 1
}

# 4. Ожидание регистрации в ADB
Write-Host "Ожидание готовности ADB..." -ForegroundColor Yellow
$timeout = 45
$elapsed = 0
$connected = $false
while ($elapsed -lt $timeout) {
    if ($emuProc.HasExited) {
        Write-Error "Эмулятор аварийно закрылся во время запуска."
        exit 1
    }
    $dev = & $ADB devices | Where-Object { $_ -match "\b(device|offline)\b" -and $_ -notmatch "List of" }
    if ($dev) {
        $connected = $true
        break
    }
    Start-Sleep -Seconds 2
    $elapsed += 2
}

if (-not $connected) {
    Write-Error "Тайм-аут ожидания подключения эмулятора к ADB ($timeout сек)."
    exit 1
}

# 5. Ожидание завершения загрузки системы
Write-Host "Ожидание полной загрузки системы Android TV..." -ForegroundColor Yellow
$bootTimeout = 90
$bootElapsed = 0
$booted = $false
while ($bootElapsed -lt $bootTimeout) {
    if ($emuProc.HasExited) {
        Write-Error "Эмулятор был закрыт до завершения загрузки системы."
        exit 1
    }
    $boot = (& $ADB shell getprop sys.boot_completed 2>$null)
    if ($boot -and $boot.Trim() -eq "1") {
        $booted = $true
        break
    }
    Start-Sleep -Seconds 2
    $bootElapsed += 2
}

if ($booted) {
    Write-Host "Эмулятор Android TV ($targetAvd) успешно запущен и готов к работе!" -ForegroundColor Green
} else {
    Write-Warning "Эмулятор запущен, но статус boot_completed еще не получен (система продолжает инициализацию)."
}