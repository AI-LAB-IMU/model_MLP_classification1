PPPGreenUploader v2 — contents
Package: com.example.ppggreendemo

Added files:
- src/main/kotlin/com/example/ppggreendemo/HttpUploader.kt
- src/main/java/com/example/ppggreendemo/HttpUploader.kt
- src/main/kotlin/com/example/ppggreendemo/PPGService.kt

Manifest merged:
- Permissions: INTERNET, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, BODY_SENSORS, com.samsung.android.health.tracking.permission.HEALTH_TRACKING
- Service: .PPGService (foregroundServiceType="health|dataSync")

Gradle (app):
- Added OkHttp/Gson deps
- Ensure AAR reference 'libs/samsung-health-tracking-1.4.1.aar'

Usage:
1) Put samsung-health-tracking-1.4.1.aar into app/libs/
2) Build & install on Galaxy Watch
3) Start app (buttons unchanged). Foreground service uploads PPG every 12s.