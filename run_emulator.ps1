# Р—Р°РїСѓСЃРє СЌРјСѓР»СЏС‚РѕСЂР° Android TV (ATV_9) Рё Р°РІС‚РѕРјР°С‚РёС‡РµСЃРєР°СЏ СѓСЃС‚Р°РЅРѕРІРєР° РїСЂРёР»РѕР¶РµРЅРёСЏ
$env:ANDROID_HOME = "D:\Projects\AndroidSDK"
$env:JAVA_HOME = "D:\Projects\AndroidSDK\jdk-17"
$ADB = "D:\Projects\AndroidSDK\platform-tools\adb.exe"
$EMU = "D:\Projects\AndroidSDK\emulator\emulator.exe"
$APK = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"

Write-Host "Р—Р°РїСѓСЃРє СЌРјСѓР»СЏС‚РѕСЂР° Android TV 9 (ATV_9)..." -ForegroundColor Cyan
Start-Process $EMU -ArgumentList "-avd", "ATV_9", "-netdelay", "none", "-netspeed", "full"

Write-Host "РћР¶РёРґР°РЅРёРµ РіРѕС‚РѕРІРЅРѕСЃС‚Рё ADB..." -ForegroundColor Yellow
& $ADB wait-for-device

Write-Host "РћР¶РёРґР°РЅРёРµ РїРѕР»РЅРѕР№ Р·Р°РіСЂСѓР·РєРё СЃРёСЃС‚РµРјС‹ Android TV..." -ForegroundColor Yellow
while ($true) {
    $boot = (& $ADB shell getprop sys.boot_completed 2>$null)
    if ($boot -and $boot.Trim() -eq "1") { break }
    Start-Sleep -Seconds 2
}

Write-Host "Р­РјСѓР»СЏС‚РѕСЂ Р·Р°РіСЂСѓР¶РµРЅ! РЈСЃС‚Р°РЅРѕРІРєР° Ru-VK-Tube..." -ForegroundColor Green
& $ADB install -r $APK

Write-Host "Р—Р°РїСѓСЃРє Ru-VK-Tube РЅР° СЌРєСЂР°РЅРµ РўР’..." -ForegroundColor Green
& $ADB shell am start -n com.ruvk.tube.debug/com.ruvk.tube.ui.main.MainActivity
