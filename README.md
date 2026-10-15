# InstaGuard v3.0 🛡️

**Intelligent Native Android Multi-App Screen Time & Focus Guardian (Instagram, YouTube, Facebook, TikTok)**

InstaGuard v3.0 is a lightweight, production-ready Native Android application built in Kotlin. It enforces independent, real-time **1-hour active usage limits** and **3-hour focus cooldown locks** for the 4 primary social media platforms: **Instagram**, **YouTube**, **Facebook**, and **TikTok**.

---

## 🚀 Key Features in v3.0

- **Independent Multi-App Architecture:** Instagram, YouTube, Facebook, and TikTok each run completely independent usage cycles and cooldown locks. Locking one platform never impedes the others.
- **Comprehensive Package Mapping:** Recognizes main, Lite, and regional package variants across all 4 platforms (e.g. `com.instagram.android`, `com.instagram.lite`, `com.google.android.youtube`, `com.facebook.katana`, `com.facebook.lite`, `com.zhiliaoapp.musically`, `com.ss.android.ugc.trill`, `com.zhiliaoapp.musically.go`).
- **High-Precision Real-Time Tracking:** Tracks active foreground usage in milliseconds with zero lag via in-memory companion state synchronized with `SharedPreferences("InstaGuardPrefs")`. Runs a continuous 100ms background enforcement loop while any tracked app is active.
- **Smooth 30 FPS Millisecond UI Timers:** Live tickers update at 33ms (~30 FPS) displaying `HH:MM:SS.mmm` with monospace formatting to eliminate horizontal digit jitter.
- **Instant Test Mode (1m Limit / 2m Lock):** Built-in dashboard toggle switch for fast testing across all platforms without waiting a full hour.
- **Dynamic Non-Bypassable Lock Screen:**
  - Dark-themed minimalist UI dynamically indicating which platform is locked: *"[App Name] is Locked for Focus"*.
  - Live millisecond countdown timer (`HH:MM:SS.mmm`) ticking down that specific app's remaining cooldown.
  - Intercepts back navigation and redirects to the Android Home screen.
  - Automatically dismisses and resets when the app's cooldown concludes.
- **YouTube Picture-in-Picture (PiP) Defense:** Aggressively detects, intercepts, and dismisses floating PiP windows using Accessibility Window APIs (`isInPictureInPicture`). Seizes exclusive transient audio focus to instantly pause background media playback.
- **Full-Screen System Overlay:** Renders an impenetrable full-screen overlay (`TYPE_ACCESSIBILITY_OVERLAY` & `TYPE_APPLICATION_OVERLAY`) on top of all windows and system dialogs, preventing PiP from floating over the lock interface.
- **Reboot Persistence & Fallback Sync:** State persisted across device reboots via `SharedPreferences`, with fallback queries to `UsageStatsManager.queryEvents()` if the service was not yet active.
- **Clean Material 3 Dashboard:**
  - Status indicator (Active / Inactive).
  - One-tap setup buttons for **Usage Access** and **Accessibility Service**.
  - Live Instagram screen time progress bar and status badge.
  - Built-in guide for Android 13+ **"Allow restricted settings"** for sideloaded APKs.

---

## 📁 Project Architecture & File Structure

```
InstaGuard/
├── .github/
│   └── workflows/
│       └── build-apk.yml               # Zero-setup GitHub Actions Cloud APK Builder
├── app/
│   ├── build.gradle                    # App-level Gradle config (SDK 34, ViewBinding, Deps)
│   ├── proguard-rules.pro              # ProGuard configuration
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml     # Permissions, activities, and accessibility service
│           ├── java/com/instaguard/app/
│           │   ├── InstaGuardApp.kt    # Application class
│           │   ├── data/
│           │   │   └── GuardPreferences.kt  # Thread-safe persistent state & settings
│           │   ├── service/
│           │   │   └── AppBlockerService.kt # AccessibilityService intercepting Instagram
│           │   ├── ui/
│           │   │   ├── MainActivity.kt      # Main dashboard & permission manager
│           │   │   └── LockScreenActivity.kt# Full-screen focus cooldown lock screen
│           │   └── util/
│           │       ├── GuardManager.kt      # State machine, allowance & cooldown timer logic
│           │       └── UsageStatsHelper.kt  # UsageStatsManager query & permission checks
│           └── res/
│               ├── drawable/           # Vector icons (shield, lock, timer, instagram, etc.)
│               ├── layout/
│               │   ├── activity_main.xml        # Dashboard layout
│               │   └── activity_lock_screen.xml # Lock screen layout
│               ├── values/             # Colors, strings, themes
│               ├── values-night/       # Dark theme
│               └── xml/
│                   ├── accessibility_service_config.xml # Accessibility service properties
│                   ├── backup_rules.xml
│                   └── data_extraction_rules.xml
├── build-apk.bat                       # 1-click Windows CLI build script
├── build-apk.ps1                       # 1-click PowerShell CLI build script
├── build.gradle                        # Root Gradle build script
├── settings.gradle                     # Gradle settings and repositories
├── gradle.properties                   # JVM parameters & AndroidX flags
├── gradlew                             # Gradle wrapper shell script (Linux/macOS)
├── gradlew.bat                         # Gradle wrapper batch script (Windows)
└── README.md                           # Documentation
```

---

## 🛠️ How to Build the APK

You can build the installable `.apk` file without ever installing or opening Android Studio using either of the two methods below:

### Method 1: Zero-Setup Cloud APK Builder (Recommended)
This repository includes a fully configured **GitHub Actions Workflow** (`.github/workflows/build-apk.yml`).

1. Push this project to a GitHub repository:
   ```bash
   git init
   git add .
   git commit -m "Initial commit of InstaGuard"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```
2. Navigate to the **Actions** tab in your GitHub repository.
3. The **"Build InstaGuard APK"** workflow will automatically start.
4. Once completed (approx. 2–3 minutes), click on the workflow run.
5. Under **Artifacts**, download **`InstaGuard-debug-apk`**. Inside you will find `InstaGuard.apk` ready to install on your phone!

---

### Method 2: Local CLI Build Script

#### Prerequisites:
- **JDK 17 or higher** installed:
  ```powershell
  # Using winget on Windows:
  winget install Microsoft.OpenJDK.17
  ```
- **Android Command-line Tools / SDK** (optional, Gradle can download missing build tools automatically).

#### Run the Build:
On Windows, you can double-click `build-apk.bat` or run in PowerShell:
```powershell
.\build-apk.ps1
```
Or use the Gradle wrapper directly:
```bash
# Windows
.\gradlew.bat assembleDebug

# Linux / macOS / Git Bash
./gradlew assembleDebug
```

#### Output APK Location:
The compiled debug APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 Device Installation & First-Time Setup

1. **Install the APK:** Transfer `app-debug.apk` to your Android device and tap to install.
2. **Handle Android 13+ "Restricted Settings" (Crucial for Sideloaded APKs):**
   - On Android 13 and 14, Android disables accessibility for apps installed outside the Play Store by default.
   - Go to phone **Settings** > **Apps** > **InstaGuard**.
   - Tap the **⋮** (three vertical dots) in the top-right corner.
   - Tap **"Allow restricted settings"** and confirm with your device PIN or fingerprint.
3. **Grant Required Permissions in InstaGuard:**
   - Launch **InstaGuard**.
   - Tap **"1. Grant Usage Access"**: Toggle InstaGuard to Allowed.
   - Tap **"2. Enable Accessibility Service"**: Turn InstaGuard ON.
4. **Active Protection:** The dashboard badge will turn **GREEN ("PROTECTION ACTIVE")**.
   - Use Instagram normally.
   - Once your accumulated foreground time reaches **60 minutes**, Instagram will immediately minimize, and the **Lock Screen** will take over with a 3-hour countdown timer.
   - Any attempt to open Instagram during the 3-hour cooldown will be instantly intercepted and redirected to the lock screen.
   - When the cooldown reaches 0, the session resets automatically.

---

## 🔒 Security & Privacy

- **100% Offline & Private:** InstaGuard requires zero internet permissions (`android.permission.INTERNET` is omitted). No data ever leaves your device.
- **Battery Efficient:** Uses passive accessibility event transitions (`TYPE_WINDOW_STATE_CHANGED`) rather than CPU-heavy background polling.
