# `ota/` — the update manifest

This folder holds **the one file every device in the fleet polls**.

`update-manifest.json` is the entire server side of the OTA system. There is no
backend, no database and no API — just a static JSON file served over HTTPS by
`raw.githubusercontent.com`, which is free and needs no maintenance.

---

## How a device uses this file

```
Device (every ~6h, via WorkManager)
   │
   ├─► GET raw.githubusercontent.com/.../master/ota/update-manifest.json
   │
   ├─► manifest.versionCode  >  installed versionCode ?
   │        no  → done, nothing happens
   │        yes → ↓
   │
   ├─► notification: "Update available"
   ├─► user taps → download apkUrl
   ├─► SHA-256 of download  ==  manifest.sha256 ?
   │        no  → delete the file, refuse to install
   │        yes → ↓
   └─► hand to PackageInstaller → system confirmation → installed
```

---

## Field reference

| Field | Required | Notes |
|---|---|---|
| `versionCode` | **yes** | The comparison key. Must be **strictly greater** than the installed code or nothing happens. |
| `versionName` | **yes** | Shown to the user. Cosmetic — never used for the comparison. |
| `apkUrl` | **yes** | Direct HTTPS link to the APK. GitHub Release asset URL. |
| `sha256` | **yes** | Lowercase hex digest. Verified after download; a mismatch aborts the install. |
| `sizeBytes` | no | Enables an accurate progress bar. |
| `minSdk` | no | Devices below this API level are never offered the update. |
| `mandatory` | no | `true` hides the "Later" button on the update screen. |
| `releaseNotes` | no | Shown in the notification and on the update screen. |
| `publishedAt` | no | Informational only. |

---

## Publishing an update

**Never hand-edit this file.** Use the script — it derives the version from
`app/build.gradle`, computes the real checksum, and refuses to publish a
`versionCode` no device would accept:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\tools\publish-update.ps1 -VersionName "1.1.0" -ReleaseNotes "Faster startup"
```

Rehearse first with `-DryRun`.

---

## The three ways this goes wrong

**1. `versionCode` was not incremented.**
The check is strictly `manifest.versionCode > installed`. Equal codes mean no
device ever offers the update. The publish script blocks this.

**2. The APK was signed with a different key.**
Android refuses to update an app whose signature changed — the install fails
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Every release, forever, must use the
same keystore. **Back that keystore up**; losing it means no device in the field
can ever be updated again.

**3. Editing the manifest on the wrong branch.**
Devices poll the branch named in `AppConfig.UPDATE_MANIFEST_URL` — currently
`master`. A manifest committed to `dev` is invisible to them.

---

## Note on caching

`raw.githubusercontent.com` caches for roughly five minutes. A device that
checks immediately after you publish may still receive the previous manifest.
The client appends a changing query parameter to defeat this, but an
intermediate proxy may still hold a copy briefly. If a fresh publish seems not
to appear, wait five minutes before investigating.

## Note on repository visibility

This repository is **public**, which is what makes the raw manifest and the
release assets downloadable by devices with no credentials. On a private
repository both URLs require an access token — and embedding a token in a
distributed APK hands it to anyone who unzips the file. If the APK must stay
private, host it somewhere with signed, expiring URLs instead.
