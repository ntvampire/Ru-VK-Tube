# RuVK Hub

Автономный каталог и плеер фильмов и сериалов для Android TV (версии 9.0+ / API 28+).

## Назначение
- Единый агрегатор контента с каталогом TMDB (популярное, тренды, новинки).
- Прямой поиск и воспроизведение видеопотоков (HLS/MP4) из российских сетей **VK Video** и **Rutube**.
- Поиск русскоязычных трейлеров по обеим сетям.
- Воспроизведение через встроенный аппаратный плеер (AndroidX Media3 / ExoPlayer) в стиле Vimu с управлением пультом ДУ или запуск внешних плееров (VLC, Vimu, Just Player).
- Полная автономность: без серверов, регистраций и авторизаций. Локальное хранение истории с таймкодами и избранного (Room SQLite).

## Сборка и запуск
Требования: JDK 17, Android SDK (API 34).

```powershell
$env:JAVA_HOME = "D:\Projects\AndroidSDK\jdk-17"
$env:ANDROID_HOME = "D:\Projects\AndroidSDK"

# Сборка Debug APK:
.\gradlew.bat assembleDebug

# Запуск локального эмулятора Android TV и автоустановка:
.\run_emulator.ps1

# Либо ручная установка на любое подключенное устройство:
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
