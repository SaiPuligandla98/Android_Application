# Release signing

**The single most important operational fact in this project:**

> Android refuses to install an update signed by a different key than the
> installed build. Every release must be signed with the **same** key, forever.
> Lose that key and **no device in the field can ever be updated again** — each
> one must be uninstalled and reinstalled by hand.

---

## Why this document exists

This project hit the failure for real. Builds up to 1.6.0 were **debug** builds
signed with a developer machine's auto-generated debug keystore. Then CI built
1.7.0 and signed it with **its own** debug keystore. Different key:

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE
signatures do not match previously installed version
```

Debug keystores are generated per-machine, on demand. They can never be a basis
for distribution. The fix is one fixed release key used by every build, local
and CI, for the life of the product.

---

## The keystore

Created once, with a 30-year validity:

```powershell
keytool -genkeypair -v `
  -keystore hcrobotics-release.jks `
  -alias hcrobotics `
  -keyalg RSA -keysize 2048 -validity 10950 `
  -dname "CN=HC Robotics, OU=Engineering, O=HC Robotics, L=Hyderabad, S=Telangana, C=IN"
```

Certificate fingerprint of the key currently in use:

```
SHA-256: 3027901713d27ee36e0986451e53de5f8b01220d9119c569d1fbfe3744333e84
```

If a build ever produces a different fingerprint, **stop** — that APK cannot
update any existing installation.

Verify any APK with:

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs your.apk
```

### ⚠️ Back it up

`hcrobotics-release.jks` and its passwords are **not in git** and never should
be — the repository is public, and anyone holding the key could sign an APK
your fleet would accept as a genuine update.

Keep at least two copies somewhere durable (password manager, encrypted backup).
**There is no recovery if it is lost.**

---

## Local builds

`keystore.properties` sits in the repository root and is gitignored:

```properties
storeFile=hcrobotics-release.jks
storePassword=…
keyAlias=hcrobotics
keyPassword=…
```

`app/build.gradle` picks it up automatically. Without the file, release builds
are unsigned and debug builds still work — so a fresh clone is never blocked.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleRelease
```

---

## CI builds

The workflow reads four repository secrets. Add them at
**Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The `.jks` file, base64-encoded (see below) |
| `KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `KEY_ALIAS` | `hcrobotics` |
| `KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

Produce the base64 blob with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("hcrobotics-release.jks")) |
    Set-Content keystore.base64.txt -NoNewline
```

Paste the contents of `keystore.base64.txt` as `KEYSTORE_BASE64`, then **delete
that file** — it is the key in plain text.

The workflow decodes it to a temporary path, builds `:app:assembleRelease`, and
**verifies the APK is signed before publishing**. An unsigned APK installs on
nothing, so failing the build is far better than shipping one.

---

## Debug vs release

| | debug | release |
|---|---|---|
| applicationId | `com.hcrobotics.testapp.debug` | `com.hcrobotics.testapp` |
| Signing key | per-machine, auto-generated | the fixed release key |
| Debuggable | yes — anyone with a cable | no |
| R8 shrinking | off | on (5.76 MB → **2.03 MB**) |
| Suitable for the field | **no** | yes |

Because the application IDs differ, the two are **separate apps** and can coexist
on one device. Moving a device from debug to release is a one-time uninstall and
reinstall; after that, OTA updates work indefinitely.

---

## The one rule

**Never change the keystore.** Not to "clean things up", not when moving CI, not
when a new developer joins. Every future release must be signed by the key whose
fingerprint is recorded above, or the fleet is stranded.
