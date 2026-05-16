# WakeUp Pro Native Android

This folder is the native Kotlin/Android Studio conversion of the original React + Capacitor prototype.

## What This Version Uses

- Kotlin source files under `app/src/main/java/com/wakeup/pro`
- Native Android views built in `MainActivity.kt`
- `AlarmManager` for alarm and snooze scheduling
- `BroadcastReceiver` for alarm triggers
- SharedPreferences-backed alarm storage
- Android notification/full-screen alarm flow
- Runtime notification permission request on Android 13+
- Native accelerometer motion challenge
- Native WiFi RSSI challenge

## Build

Open this folder directly in Android Studio, or run:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
