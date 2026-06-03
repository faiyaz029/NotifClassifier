# Notification Classifier Android App
## Complete Setup Guide (Ubuntu → Redmi 8A)

---

## TECHNOLOGY RECOMMENDATION: Option A — Pure Native Kotlin

**Use Option A (pure native Kotlin). Here's why:**

| Factor | Option A: Pure Kotlin | Option B: Flutter + MethodChannel |
|---|---|---|
| NotificationListenerService | Direct, zero glue | Requires MethodChannel + EventChannel wiring |
| MIUI compatibility | Native service, best possible | Flutter runtime adds another killable layer |
| Dependencies | 4 AndroidX libs | Flutter SDK + Dart runtime + bridging code |
| Debug cycle | Android Studio native | Two runtimes to debug |
| Setup complexity | Standard Android project | Flutter SDK install + channel code |

Flutter's NotificationListenerService packages fail on MIUI because they create an extra process boundary. A pure Kotlin service runs directly in the system's process slot — there is nothing extra to kill.

---

## PROJECT FILE STRUCTURE

```
NotifClassifier/
├── build.gradle                          ← project-level Gradle
├── settings.gradle
├── gradle.properties
└── app/
    ├── build.gradle                      ← app-level Gradle + dependencies
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/notifclassifier/
        │   ├── MainActivity.kt           ← Permission screen + Feed UI + Adapter
        │   ├── model/
        │   │   └── Models.kt             ← Data classes
        │   ├── network/
        │   │   └── ApiClient.kt          ← HTTP client (no Retrofit needed)
        │   ├── service/
        │   │   ├── NotifListenerService.kt  ← Core NLS + background classification
        │   │   └── BootReceiver.kt          ← MIUI resilience
        │   └── ui/
        │       └── NotificationRepository.kt ← In-memory store + CSV export
        └── res/
            ├── layout/
            │   ├── activity_main.xml
            │   └── item_notification.xml
            ├── drawable/
            │   └── badge_bg.xml
            ├── values/
            │   ├── strings.xml
            │   └── themes.xml
            └── xml/
                └── notification_listener_service.xml
```

---

## WHERE TO CHANGE THE BACKEND URL

Open `app/src/main/kotlin/com/notifclassifier/network/ApiClient.kt`

Look for this block (around line 20):

```kotlin
// ▼▼▼  BACKEND URL — change here if you redeploy  ▼▼▼
private const val BASE_URL = "https://faiyaz029-notif-classifier.hf.space"
// ▲▲▲  BACKEND URL ▲▲▲
```

Change the URL string. That is the **only place** you need to edit.

---

## STEP-BY-STEP SETUP ON UBUNTU

### Step 1 — Open the project in Android Studio

1. Launch Android Studio on Ubuntu
2. **File → Open** → navigate to the `NotifClassifier/` folder → click **OK**
3. Wait for Gradle sync to complete (first time downloads ~200 MB)
4. If Gradle asks to upgrade the wrapper, click **Don't remind me again** and proceed

### Step 2 — Set up your Redmi 8A for USB debugging

On the phone:
1. **Settings → About phone** → tap **MIUI version** 7 times until "You are now a developer" appears
2. **Settings → Additional settings → Developer options**:
   - Enable **USB debugging**
   - Enable **Install via USB**
   - Set **USB configuration** to **MTP** (not charging-only)
3. On MIUI specifically, also go to **Settings → Additional settings → Developer options → Turn off MIUI optimization** (reboot required) — this prevents MIUI from blocking the notification listener

On Ubuntu:
```bash
# Install ADB if not already present
sudo apt update && sudo apt install adb

# Plug in the phone, then:
adb devices
# Should show your device serial number with "device" status
# If it shows "unauthorized", check the phone for an RSA key dialog and tap Allow
```

### Step 3 — Configure MIUI battery optimization exception

This is the most important step for reliable background operation:

1. **Settings → Battery & performance → App battery saver**
2. Find "Notif Classifier" → set to **No restrictions**
3. Also: **Settings → Apps → Manage apps → Notif Classifier → Battery saver → No restrictions**
4. **Settings → Security → Autostart** → enable autostart for Notif Classifier

### Step 4 — Build and run

In Android Studio:
1. In the top toolbar, confirm your Redmi 8A is selected in the device dropdown
2. Click the green **▶ Run** button (or press Shift+F10)
3. The app installs and launches on the phone automatically

### Step 5 — Grant notification access

1. The app opens on the **Permission screen**
2. Tap **"Open Notification Settings"**
3. The system Settings → Notification access screen opens
4. Find **"Notif Classifier"** and toggle it **ON**
5. Confirm the dialog that appears
6. Press the back button to return to the app
7. The app automatically detects the permission was granted and switches to the **main feed screen**

---

## HOW THE MIUI RESILIENCE WORKS

| Mechanism | What it does |
|---|---|
| `startForeground()` | Keeps the service in the "foreground services" bucket — Android cannot kill these without user action |
| `START_STICKY` | If killed anyway, Android restarts the service automatically |
| `BootReceiver` | Restarts the service on device reboot or MIUI quick-boot |
| `onDestroy()` broadcast | When the service is killed, it fires a broadcast to `BootReceiver` to request a restart |
| `onListenerDisconnected()` + `requestRebind()` | When the system temporarily unbinds the listener (common on MIUI), requests reconnection immediately |

If MIUI still kills the service after all this, go to **Settings → Battery & performance → Choose apps → Notif Classifier → No restrictions**. This is a per-device MIUI setting that cannot be set programmatically.

---

## RUNNING ON rmx3085 (Realme 8) or Samsung

The same steps apply. The MIUI-specific boot actions in `BootReceiver` are harmless on other devices. For Samsung OneUI, the equivalent is:

- **Settings → Device care → Battery → Background usage limits** → add the app to "Never sleeping apps"

---

## CSV EXPORT

Tap **"Export CSV"** in the top toolbar. The file is saved to:

```
/sdcard/Android/data/com.notifclassifier/files/Documents/notifications_YYYYMMDD_HHMMSS.csv
```

To pull it to your Ubuntu machine:
```bash
adb pull /sdcard/Android/data/com.notifclassifier/files/Documents/ ~/Desktop/notif_exports/
```

The CSV has these columns:
`id, app_label, package_name, title, text, post_time, decision_code, decision_label, confidence, user_rating`

---

## BUILDING A RELEASE APK (optional)

```
Build → Generate Signed Bundle / APK → APK → Create new keystore → fill in details → Release → Finish
```

The signed APK will be at:
```
app/release/app-release.apk
```

To install directly via ADB:
```bash
adb install app/release/app-release.apk
```

---

## TROUBLESHOOTING

| Problem | Fix |
|---|---|
| `adb devices` shows `unauthorized` | Tap "Allow" on the RSA dialog on the phone |
| Gradle sync fails with "SDK not found" | Android Studio → SDK Manager → install Android 14 (API 34) SDK |
| Notifications not appearing | Check MIUI autostart and battery optimization settings above |
| App crashes on launch | Check Logcat in Android Studio; filter by "NotifClassifier" |
| Server unreachable error | Confirm the Hugging Face Space is awake (visit the URL in a browser — free tier spaces sleep after inactivity) |
| Stars don't show | Classification hasn't returned yet — wait a few seconds; if it never arrives, the server may be sleeping |

---

## BACKEND API REFERENCE (for reference only — do not modify)

```
POST https://faiyaz029-notif-classifier.hf.space/classify
Body: { "app_name": "com.whatsapp", "user_name": "Alice", "content": "Hey" }
Response: { "decision_code": 1, "decision_label": "Notify Instantly", "confidence": 0.91 }

POST https://faiyaz029-notif-classifier.hf.space/feedback
Body: { "app_name": "...", "user_name": "...", "content": "...", "decision_code": 1, "user_rating": 5 }
Response: { "success": true, "message": "...", "log_id": 42 }

GET https://faiyaz029-notif-classifier.hf.space/
Response: { "status": "ok", "message": "..." }
```
