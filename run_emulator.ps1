# Запуск эмулятора Android TV (ATV_9) и автоматическая установка приложения
$env:ANDROID_HOME = "D:\Projects\AndroidSDK"
$env:JAVA_HOME = "D:\Projects\AndroidSDK\jdk-17"
$ADB = "D:\Projects\AndroidSDK\platform-tools\adb.exe"
$EMU = "D:\Projects\AndroidSDK\emulator\emulator.exe"
$APK = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"

Write-Host "Запуск эмулятора Android TV 9 (ATV_9)..." -ForegroundColor Cyan
Start-Process $EMU -ArgumentList "-avd", "ATV_9", "-netdelay", "none", "-netspeed", "full"

Write-Host "Ожидание готовности ADB..." -ForegroundColor Yellow
& $ADB connect 127.0.0.1:5555 | Out-Null
& $ADB wait-for-device

Write-Host "Ожидание полной загрузки системы Android TV..." -ForegroundColor Yellow
while ($true) {
    & $ADB connect 127.0.0.1:5555 | Out-Null
    $boot = (& $ADB -s 127.0.0.1:5555 shell getprop sys.boot_completed 2>$null)
    if ($boot -and $boot.Trim() -eq "1") { break }
    Start-Sleep -Seconds 2
}

Write-Host "Эмулятор загружен! Установка Ru-VK-Tube..." -ForegroundColor Green
& $ADB -s 127.0.0.1:5555 install -r $APK

Write-Host "Запуск Ru-VK-Tube на экране ТВ..." -ForegroundColor Green
& $ADB -s 127.0.0.1:5555 shell am start -n com.ruvk.tube.debug/com.ruvk.tube.ui.main.MainActivity
