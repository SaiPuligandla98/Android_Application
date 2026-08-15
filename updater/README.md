# `:updater` — plug-and-play OTA update module

A self-contained Android library that lets an app installed **outside the Play
Store** discover, download, verify and install newer builds of itself.

Built for small managed fleets — 10 to 50 phones or tablets in the field — and
designed to cost **nothing** to operate. Java + XML only, no Kotlin.

---

## 1. What it does

```
   YOU                          GITHUB (free)                    DEVICE
   ───                          ─────────────                    ──────
   publish-update.ps1
     ├ build APK
     ├ SHA-256          ──►  Release asset: app.apk
     └ write manifest   ──►  ota/update-manifest.json
                                    │
                                    │  polled every N hours
                                    │  (WorkManager, survives reboot)
                                    ▼
                                                        versionCode newer?
                                                              ▼
                                                        🔔 notification
                                                              ▼
                                                        download + verify
                                                              ▼
                                                        PackageInstaller
                                                              ▼
                                                        system confirm → done
```

**No server. No Firebase required. No monthly bill.**

---

## 2. Integration — three steps

### Step 1 — add the module

Copy the `updater/` folder into your project, then in `settings.gradle`:

```groovy
include ':updater'
```

and in your app's `build.gradle`:

```groovy
dependencies {
    implementation project(':updater')
}
```

### Step 2 — initialise it

One call in your `Application.onCreate()`:

```java
public final class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        OtaUpdater.initialise(this, new OtaConfig.Builder()
                .manifestUrl("https://raw.githubusercontent.com/OWNER/REPO/master/ota/update-manifest.json")
                .checkIntervalHours(6)
                .build());
    }
}
```

### Step 3 — publish a release

```powershell
.\tools\publish-update.ps1 -VersionName "1.1.0" -ReleaseNotes "Faster startup"
```

**That is the entire integration.** Permissions, the update Activity, the
install-result receiver and the background schedule all arrive automatically
through manifest merging.

---

## 3. Public API

| Method | Purpose |
|---|---|
| `OtaUpdater.initialise(context, config)` | Configure + schedule recurring checks. Call from `Application.onCreate()`. |
| `OtaUpdater.checkNow(context)` | Check immediately — for a "Check for updates" button, or a push message. |
| `OtaUpdater.getPendingUpdate(context)` | The discovered update, or `null`. For an in-app badge. |
| `OtaUpdater.openUpdateScreen(context)` | Open the update screen for the pending update. |
| `OtaUpdater.needsInstallPermission(context)` | Has the user granted "install unknown apps"? |
| `OtaUpdater.canPostNotifications(context)` | Would a notification actually be shown? |
| `OtaUpdater.cancelChecks(context)` | Stop all scheduled checks. |

### Configuration options

```java
new OtaConfig.Builder()
    .manifestUrl("https://…/update-manifest.json")  // required, must be HTTPS
    .checkIntervalHours(6)                          // default 6
    .requireUnmeteredNetwork(false)                 // default false (Wi-Fi only if true)
    .notificationIcon(R.drawable.ic_stat_update)    // default: module's own icon
    .debugLogging(true)                             // default: module's build type
    .build();
```

---

## 4. Instant push (optional, also free)

Polling every few hours suits most fleets and needs no infrastructure at all.
When an update must land *now*, wire Firebase Cloud Messaging — free and
unlimited — to the existing hook:

```java
public final class MyMessagingService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        OtaUpdater.checkNow(this);   // that is the whole integration
    }
}
```

**This module has no Firebase dependency.** Push is one method call, so adding
it is your app's choice and never this library's problem. Send to all devices at
once by publishing to an FCM *topic* the app subscribes to.

---

## 5. Two permissions you must handle

### `REQUEST_INSTALL_PACKAGES` — "install unknown apps"

Declared by the module, but on Android 8.0+ the user must **also** grant it in
Settings. It cannot be requested with a normal permission dialog. The update
screen detects this and sends them to the right page, but it is far better to
ask once during onboarding:

```java
if (OtaUpdater.needsInstallPermission(this)) {
    // Explain why, then send the user to grant it.
}
```

### `POST_NOTIFICATIONS` — Android 13+

A runtime permission. **Declined, every update notification is silently
dropped** — no error, nothing. The module cannot request it (a permission dialog
must be launched from an Activity), so your app must:

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && !OtaUpdater.canPostNotifications(this)) {
    requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 1);
}
```

---

## 6. Signing — read this before your first release

**Android refuses to update an app with an APK signed by a different key.**

| Scenario | Result |
|---|---|
| debug → debug | ✅ works (shared debug keystore) |
| release → release, same keystore | ✅ works |
| debug → release, or a regenerated keystore | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |

Create the keystore **once**:

```powershell
keytool -genkeypair -v -keystore hcrobotics-release.jks `
        -keyalg RSA -keysize 2048 -validity 10000 -alias hcrobotics
```

Then `keystore.properties` in the repo root (gitignored):

```properties
storeFile=../hcrobotics-release.jks
storePassword=…
keyAlias=hcrobotics
keyPassword=…
```

> **Back that `.jks` file up somewhere safe.** Lose it and no device in the
> field can ever be updated again — every one must be uninstalled and
> reinstalled by hand.

---

## 7. Security model

Three independent layers, all of which an attacker would have to defeat:

| Layer | Protects against |
|---|---|
| **HTTPS enforced** (rejected at every redirect hop) | Manifest or APK substituted in transit |
| **SHA-256 verified** after download, before install | Corruption, truncation, a tampered host |
| **Android signature check** | An APK signed by anyone but you |

Downloads land in app-private internal storage, so no other app can read or
swap the file between verification and installation. `PackageInstaller` streams
the bytes directly, so no `FileProvider` is needed and the APK is never exposed.

---

## 8. Internal structure

```
updater/src/main/java/com/hcrobotics/updater/
├── OtaUpdater.java              PUBLIC API — the static facade
├── OtaConfig.java               PUBLIC — builder + persisted settings
├── UpdateInfo.java              PUBLIC — parsed manifest, immutable
│
├── work/UpdateCheckWorker.java  WorkManager job: fetch, compare, notify
├── notify/UpdateNotifier.java   Channel + notifications
├── ui/UpdateActivity.java       Update screen (explicit state machine)
│
└── internal/
    ├── HttpSupport.java         Redirect handling + HTTPS enforcement
    ├── ManifestFetcher.java     Fetch + parse, with cache-busting
    ├── ApkDownloader.java       Streamed download + verification
    ├── Digest.java              SHA-256
    ├── ApkInstaller.java        PackageInstaller session
    ├── InstallResultReceiver.java  Install outcome + user confirmation
    ├── UpdateStorage.java       Where APKs live; cleanup
    ├── AppVersion.java          Installed version (API-safe)
    └── UpdaterLog.java          Tagged logging
```

**Dependencies:** AppCompat, Core, ConstraintLayout, WorkManager. That's it —
no Retrofit, no OkHttp, no Gson, no Firebase, no Material. `HttpURLConnection`
and `org.json` ship with Android, and Material components are avoided because
they crash under a non-Material host theme.

---

## 9. Re-branding per app

Library resources merge into the host app's resource table, and **the app wins
any name collision**. Override anything by declaring the same name in your app:

```xml
<!-- app/src/main/res/values/colors.xml -->
<color name="updater_color_primary">#FF6200EE</color>

<!-- app/src/main/res/values/strings.xml -->
<string name="updater_notification_title">System update</string>
```

No fork, no change to this module.

---

## 10. Debugging

```powershell
adb logcat -s HCOta
```

Every line the module emits carries the `HCOta` tag.

| Symptom | Cause |
|---|---|
| Nothing happens on schedule | Android batches background work. Use `OtaUpdater.checkNow()` to force it. |
| "Update available" never appears | `POST_NOTIFICATIONS` denied (Android 13+), or `versionCode` was not incremented. |
| Install dialog never opens | "Install unknown apps" not granted. |
| `STATUS_FAILURE_CONFLICT` | Signature mismatch — different keystore. |
| Checksum mismatch | Manifest `sha256` is stale. Re-run the publish script rather than hand-editing. |
| Manifest looks stale | `raw.githubusercontent` CDN cache, ~5 minutes. |

---

## 11. Known limits

* **No delta updates.** The whole APK is downloaded each time. Fine at ~5 MB
  across 50 devices; reconsider for a 100 MB app.
* **No silent install.** Android always shows its confirmation dialog unless the
  app is a device owner via an MDM enrolment. Fully unattended updates need
  Device Owner provisioning, which is a separate exercise.
* **No staged rollout.** Every device sees the same manifest. Percentage
  rollouts would need per-device logic in the manifest.
* **No automatic rollback.** If a release is bad, publish a higher `versionCode`
  containing the fix — a lower code will never be offered.
