# HC Robotics — Android Application

A native Android application built with **Java + XML**, structured as a clean,
documented starting point for experimenting with Android architecture and
platform features.

The app currently does one thing end to end: it shows a branded splash screen,
then hands off to a landing screen. Everything around that — the module layout,
theming system, logging facade, build configuration and documentation — is
production-shaped so that new features can be added without first having to
untangle a prototype.

---

## What you see when you run it

| Screen | What it does |
| ------ | ------------ |
| **Splash** | HC ROBOTICS wordmark on the brand surface, with the build version in the bottom corner. Visible for 1.5 s. |
| **Main** | Centred welcome message: *"Welcome to HC Robotics Android Application"*. |

The launcher icon is the **HC** mark from the brand artwork, shipped as an
adaptive icon so it renders correctly in every launcher shape, plus legacy PNGs
for Android 7.

---

## Technology choices

| Area | Choice | Why |
| ---- | ------ | --- |
| Language | Java 17 | Requested stack; universally readable. |
| UI | XML layouts + View Binding | Declarative layouts with compile-time-safe view access. |
| Layout engine | ConstraintLayout | Flat hierarchy, adapts to any screen without variant files. |
| Theming | Material 3 `DayNight` | Light and dark mode with no runtime branching. |
| Build | Gradle 8.12.1 + AGP 8.8.0 | Current stable toolchain. |
| Versions | Gradle version catalog | One file owns every dependency version. |

| SDK level | Value | Meaning |
| --------- | ----- | ------- |
| `compileSdk` | 35 | Compiled against Android 15 APIs. |
| `targetSdk` | 35 | Tested against Android 15 behaviour. |
| `minSdk` | 24 | Installs on Android 7.0 and newer. |

---

## Over-the-air updates

The app updates itself in the field without the Play Store. Publishing a release
is one command:

```powershell
.\tools\publish-update.ps1 -VersionName "1.1.0" -ReleaseNotes "Faster startup"
```

Devices poll a static JSON manifest, compare version codes, notify the user,
download, verify the SHA-256 and install. **No server and no running cost** —
GitHub Releases hosts the APK and `raw.githubusercontent` serves the manifest.

The whole system lives in the reusable [`:updater`](updater/README.md) module,
which drops into any Android project with one dependency line and one
`initialise()` call. See also [`ota/README.md`](ota/README.md) for the manifest
format.

---

## Project layout

```
Andoird_Test_App/
├── settings.gradle              Modules + repository sources
├── build.gradle                 Plugin declarations shared by all modules
├── gradle.properties            Build environment tuning (committed)
├── local.properties             SDK path — machine-specific, NOT committed
├── gradle/
│   └── libs.versions.toml       Version catalog: every dependency version
│
├── updater/                     REUSABLE OTA UPDATE MODULE (see its README)
│   └── src/main/java/com/hcrobotics/updater/
│       ├── OtaUpdater.java      Public API: initialise / checkNow
│       ├── OtaConfig.java       Builder + persisted settings
│       ├── UpdateInfo.java      Parsed manifest
│       ├── work/                WorkManager periodic check
│       ├── notify/              Update notifications
│       ├── ui/                  Update screen
│       └── internal/            HTTP, download, SHA-256, PackageInstaller
│
├── ota/
│   └── update-manifest.json     What devices poll. Generated, never hand-edited
│
├── tools/
│   └── publish-update.ps1       Build + release + publish manifest
│
├── app/
│   ├── build.gradle             Application module build configuration
│   ├── proguard-rules.pro       R8 keep rules for the release build
│   └── src/main/
│       ├── AndroidManifest.xml  Components, icon, theme declared to the OS
│       │
│       ├── java/com/hcrobotics/testapp/
│       │   ├── HcRoboticsApplication.java     Process-wide startup hook
│       │   ├── core/
│       │   │   ├── config/AppConfig.java      Behavioural constants
│       │   │   └── util/AppLogger.java        Logging facade
│       │   └── ui/
│       │       ├── base/BaseActivity.java     Shared Activity behaviour
│       │       ├── splash/SplashActivity.java Branded launch screen
│       │       └── main/MainActivity.java     Landing screen
│       │
│       └── res/
│           ├── layout/          activity_splash.xml, activity_main.xml
│           ├── values/          strings, colors, dimens, themes (light)
│           ├── values-night/    themes (dark mode overrides)
│           ├── drawable/        bg_splash.xml (splash window background)
│           ├── drawable-nodpi/  HC ROBOTICS wordmark artwork
│           ├── mipmap-*/        Launcher icons, all densities
│           └── xml/             Backup and data-extraction rules
│
└── docs/
    └── ARCHITECTURE.md          How the pieces fit together, and why
```

Every file above carries a header comment explaining what it is for. That is
deliberate: the comments are the documentation, kept next to the thing they
describe so they cannot drift out of date.

---

## Building and running

### Prerequisites

* **Android SDK** with platform 35 and build-tools 35.0.0.
* **JDK 17 or newer.** The JDK bundled with Android Studio works:
  `C:\Program Files\Android\Android Studio\jbr`
* A device with USB debugging on, or an emulator.

### One-time setup

`local.properties` must point at your SDK. The committed copy contains:

```properties
sdk.dir=C\:\\Users\\Admin\\AppData\\Local\\Android\\Sdk
```

Gradle also needs `JAVA_HOME` set to a valid JDK. In PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

> Set this permanently under *System Properties → Environment Variables* to
> avoid repeating it in every terminal session.

### Common commands

```powershell
# Build the debug APK
.\gradlew.bat :app:assembleDebug

# Build, install and launch on the connected device
.\gradlew.bat :app:installDebug
adb shell am start -n "com.hcrobotics.testapp.debug/com.hcrobotics.testapp.ui.splash.SplashActivity"

# Build the optimised release APK (unsigned)
.\gradlew.bat :app:assembleRelease

# Run unit tests / lint
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug

# Start clean
.\gradlew.bat clean
```

The APK lands at:

```
app/build/outputs/apk/debug/HCRobotics-1.0.0-debug.apk
```

### Debug and release install side by side

The debug build carries an `applicationIdSuffix` of `.debug`, so it installs as
a **separate app** from a release build and the two can sit on one device at the
same time.

| Build type | Installed package |
| ---------- | ----------------- |
| debug | `com.hcrobotics.testapp.debug` |
| release | `com.hcrobotics.testapp` |

Use the right one in `adb` commands — this is the most common source of
"why isn't my change showing up?"

---

## Watching the logs

Every log line the app emits is tagged with the prefix `HCR/`:

```powershell
adb logcat | Select-String "HCR/"
```

You will see the full lifecycle trace of each screen, which makes navigation and
state problems visible without adding a single temporary log statement.

Debug and info logs are compiled out of release builds — see
`core/util/AppLogger.java` for the reasoning.

---

## Where to make your next change

| I want to… | Go to |
| ---------- | ----- |
| Change the welcome text | `res/values/strings.xml` |
| Change how long the splash lasts | `core/config/AppConfig.java` |
| Change brand colours | `res/values/colors.xml` |
| Change how colours are *applied* | `res/values/themes.xml` (+ `values-night/`) |
| Add a view to the main screen | `res/layout/activity_main.xml` |
| Add a whole new screen | New package under `ui/`, then declare it in `AndroidManifest.xml` |
| Add a library | `gradle/libs.versions.toml`, then `app/build.gradle` |

| Publish an app update to devices | `tools/publish-update.ps1` |
| Change the update check interval | `core/config/AppConfig.java` |

For the reasoning behind the structure, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
For branching, commits and releases, read [docs/GIT_WORKFLOW.md](docs/GIT_WORKFLOW.md).

---

## Known gaps

These are deliberate omissions for a starting point, not oversights:

* **No release signing config.** `assembleRelease` produces an unsigned APK.
  Add a `signingConfigs` block reading credentials from the environment before
  distributing anything.
* **No tests yet.** The test dependencies and source-set wiring are in place;
  `src/test/` and `src/androidTest/` are empty.
* **The splash uses a fixed timer.** Correct while there is no startup work.
  Once there is, navigate when that work finishes instead — see the notes in
  `SplashActivity.java`.
