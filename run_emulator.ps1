[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$env:ANDROID_HOME = "D:\Projects\AndroidSDK"
$env:JAVA_HOME = "D:\Projects\AndroidSDK\jdk-17"
$ADB = "D:\Projects\AndroidSDK\platform-tools\adb.exe"
$EMU = "D:\Projects\AndroidSDK\emulator\emulator.exe"

$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { "D:\Projects\Ru-VK-Tube" }

# Поиск свежего релизного APK (*.apk в release), иначе debug
$releaseApk = Get-ChildItem "$ScriptDir\app\build\outputs\apk\release\*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($releaseApk) {
    $APK = $releaseApk.FullName
    $PKG = "com.ruvk.tube"
    Write-Host "Выбран релизный APK: $($releaseApk.Name)" -ForegroundColor Cyan
} else {
    $APK = "$ScriptDir\app\build\outputs\apk\debug\app-debug.apk"
    $PKG = "com.ruvk.tube.debug"
    Write-Host "Релизный APK не найден, выбран debug: $APK" -ForegroundColor Yellow
}

# Проверяем, запущен ли уже эмулятор
$devices = & $ADB devices | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of" }

if (-not $devices) {
    # Проверяем доступные AVD (ATV_9 или ATV_11)
    $avd = "ATV_9"
    $availableAvds = & $EMU -list-avds
    if ($availableAvds -notcontains "ATV_9" -and $availableAvds -contains "ATV_11") {
        $avd = "ATV_11"
    }

    Write-Host "Запуск эмулятора Android TV ($avd)..." -ForegroundColor Cyan
    Start-Process $EMU -ArgumentList "-avd", $avd, "-netdelay", "none", "-netspeed", "full"

    Write-Host "Ожидание готовности ADB..." -ForegroundColor Yellow
    & $ADB wait-for-device

    Write-Host "Ожидание полной загрузки системы Android TV..." -ForegroundColor Yellow
    while ($true) {
        $boot = (& $ADB shell getprop sys.boot_completed 2>$null)
        if ($boot -and $boot.Trim() -eq "1") { break }
        Start-Sleep -Seconds 2
    }
    Write-Host "Эмулятор успешно загружен!" -ForegroundColor Green
} else {
    Write-Host "Обнаружено уже запущенное устройство/эмулятор." -ForegroundColor Green
}

if (Test-Path $APK) {
    Write-Host "Установка $([System.IO.Path]::GetFileName($APK))..." -ForegroundColor Green
    & $ADB install -r $APK

    Write-Host "Перезапуск RuVK Hub ($PKG)..." -ForegroundColor Green
    & $ADB shell am force-stop $PKG
    & $ADB shell am start -n "$PKG/com.ruvk.tube.ui.main.MainActivity"
} else {
    Write-Host "APK не найден для установки. Запустите приложение вручную на ТВ." -ForegroundColor Yellow
}