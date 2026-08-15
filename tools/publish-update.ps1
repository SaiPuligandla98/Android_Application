<#
.SYNOPSIS
    Publishes a new over-the-air update: builds the APK, uploads it to a GitHub
    Release, and regenerates the update manifest the devices poll.

.DESCRIPTION
    This is the entire server side of the OTA system. There is no backend to
    deploy and nothing to pay for - GitHub Releases hosts the APK and
    raw.githubusercontent serves the manifest.

    WHAT IT DOES, IN ORDER
      1. Reads versionCode and versionName out of app/build.gradle, so the
         published manifest can never disagree with the APK it describes.
      2. Refuses to continue if that versionCode is not greater than the one
         already published - the single most common way to ship an "update"
         that no device will ever offer.
      3. Builds the APK.
      4. Computes its SHA-256. Devices verify this after downloading and refuse
         to install anything that does not match.
      5. Creates a GitHub Release and uploads the APK as an asset.
      6. Writes ota/update-manifest.json and commits it to the branch the
         devices are configured to poll.

    HOW A DEVICE SEES IT
      The app checks the manifest every few hours (WorkManager). When its
      versionCode exceeds the installed one, the user gets a notification.

.PARAMETER VersionName
    Version name for this release, e.g. "1.1.0". Must already be set in
    app/build.gradle; this parameter only cross-checks it.

.PARAMETER ReleaseNotes
    User-facing summary shown in the notification and on the update screen.

.PARAMETER Mandatory
    Marks the release as required. The update screen hides its "Later" button.

.PARAMETER BuildType
    "release" (default) or "debug".

    IMPORTANT: an OTA update must be signed with the SAME key as the build
    already installed. A debug build can only update another debug build. For a
    real fleet always use "release", with keystore.properties configured.

.PARAMETER Branch
    Branch the manifest is committed to. Must match the branch in
    AppConfig.UPDATE_MANIFEST_URL. Defaults to "master".

.PARAMETER DryRun
    Do everything except create the release and push - useful for a rehearsal.

.EXAMPLE
    .\tools\publish-update.ps1 -VersionName "1.1.0" -ReleaseNotes "Faster startup"

.EXAMPLE
    .\tools\publish-update.ps1 -VersionName "1.2.0" -ReleaseNotes "Critical fix" -Mandatory

.NOTES
    Requires: gh CLI authenticated with write access to the repository,
              JAVA_HOME pointing at a JDK 17+.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$VersionName,

    [Parameter(Mandatory = $true)]
    [string]$ReleaseNotes,

    [switch]$Mandatory,

    [ValidateSet("release", "debug")]
    [string]$BuildType = "release",

    [string]$Branch = "master",

    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# The repository root is the parent of the tools/ directory this script lives in.
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$ManifestPath = Join-Path $RepoRoot "ota\update-manifest.json"
$AppBuildGradle = Join-Path $RepoRoot "app\build.gradle"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "    $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "    $Message" -ForegroundColor Yellow
}

# =============================================================================
#  1. Read the version out of app/build.gradle
# =============================================================================
# Parsing the build file rather than accepting a versionCode parameter means the
# manifest cannot drift from the APK. A manifest advertising version 5 while the
# uploaded APK is version 4 produces an update the device downloads, installs,
# and then offers again forever.
Write-Step "Reading version from app/build.gradle"

$gradleText = Get-Content $AppBuildGradle -Raw

$versionCodeMatch = [regex]::Match($gradleText, 'versionCode\s+(\d+)')
$versionNameMatch = [regex]::Match($gradleText, 'versionName\s+"([^"]+)"')

if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw "Could not find versionCode / versionName in $AppBuildGradle."
}

$VersionCode = [int]$versionCodeMatch.Groups[1].Value
$GradleVersionName = $versionNameMatch.Groups[1].Value

Write-Ok "versionCode = $VersionCode"
Write-Ok "versionName = $GradleVersionName"

if ($GradleVersionName -ne $VersionName) {
    throw @"
Version mismatch.
  You passed  -VersionName '$VersionName'
  build.gradle says          '$GradleVersionName'

Update versionName (and increment versionCode) in app/build.gradle first, then
re-run. The manifest must describe the APK exactly.
"@
}

# =============================================================================
#  2. Refuse to publish a version the devices would ignore
# =============================================================================
# A device offers an update only when manifest.versionCode > installed
# versionCode. Publishing the same or a lower code produces a release that
# silently does nothing, and hours of confused debugging.
Write-Step "Checking against the currently published manifest"

$previousRelease = $null

if (Test-Path $ManifestPath) {
    $published = Get-Content $ManifestPath -Raw | ConvertFrom-Json
    Write-Ok "Currently published: versionCode $($published.versionCode) ($($published.versionName))"

    <#
        The release being REPLACED becomes the rollback target of the new one.

        This is the only place that information exists. A device that has taken
        a bad update cannot ask a server where the previous APK lives, because
        there is no server - so the manifest has to carry it.

        Only one level is kept: the previous release's own `previous` is
        dropped. Rollback goes back one version, not to an arbitrary point in
        history, which keeps the manifest small and the decision simple.
    #>
    $previousRelease = [ordered]@{
        versionCode = $published.versionCode
        versionName = $published.versionName
        apkUrl      = $published.apkUrl
        sha256      = $published.sha256
        sizeBytes   = $published.sizeBytes
    }

    if ($VersionCode -le $published.versionCode) {
        throw @"
versionCode $VersionCode is not greater than the published $($published.versionCode).

No device would ever offer this update: the check is strictly
'manifest.versionCode > installed versionCode'.

Increment versionCode in app/build.gradle and re-run.
"@
    }
} else {
    Write-Warn "No manifest published yet; this will be the first release."
}

# =============================================================================
#  3. Build the APK
# =============================================================================
Write-Step "Building the $BuildType APK"

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "JAVA_HOME is not set to a valid JDK. Set it to a JDK 17+, e.g. 'C:\Program Files\Android\Android Studio\jbr'."
}

$gradleTask = if ($BuildType -eq "release") { ":app:assembleRelease" } else { ":app:assembleDebug" }
& (Join-Path $RepoRoot "gradlew.bat") $gradleTask --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "The Gradle build failed. Fix the build before publishing."
}

$ApkPath = Join-Path $RepoRoot "app\build\outputs\apk\$BuildType\HCRobotics-$GradleVersionName-$BuildType.apk"
if (-not (Test-Path $ApkPath)) {
    throw "Expected APK not found at $ApkPath"
}

$ApkFile = Get-Item $ApkPath
Write-Ok "Built $($ApkFile.Name) ($([math]::Round($ApkFile.Length / 1MB, 2)) MB)"

# ---- Signing sanity check ---------------------------------------------------
# An unsigned release APK installs on nothing. Catching it here saves a failed
# rollout that only shows up on the devices.
if ($BuildType -eq "release" -and -not (Test-Path (Join-Path $RepoRoot "keystore.properties"))) {
    throw @"
keystore.properties is missing, so this release APK is UNSIGNED and cannot be
installed as an update on any device.

Create the keystore once:
  keytool -genkeypair -v -keystore hcrobotics-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias hcrobotics

Then create keystore.properties in the repository root (it is gitignored):
  storeFile=../hcrobotics-release.jks
  storePassword=...
  keyAlias=hcrobotics
  keyPassword=...

Every future release MUST use this same keystore. Back it up.
"@
}

# =============================================================================
#  4. Compute the SHA-256 the devices will verify against
# =============================================================================
# This is the module's integrity guarantee. A device that downloads bytes whose
# digest does not match this value deletes them and refuses to install.
Write-Step "Computing SHA-256"

$Sha256 = (Get-FileHash -Path $ApkPath -Algorithm SHA256).Hash.ToLower()
Write-Ok $Sha256

# =============================================================================
#  5. Publish the APK as a GitHub Release asset
# =============================================================================
# Release assets, not raw file URLs: raw is capped at 100 MB, bloats every clone
# with binaries, and has no CDN. Releases are built for exactly this.
Write-Step "Publishing the GitHub Release"

$Tag = "v$GradleVersionName"
$RepoSlug = (gh repo view --json nameWithOwner --jq '.nameWithOwner')
if ($LASTEXITCODE -ne 0) {
    throw "Could not read the repository from gh. Is the GitHub CLI authenticated with write access?"
}
Write-Ok "Repository: $RepoSlug"

$ApkUrl = "https://github.com/$RepoSlug/releases/download/$Tag/$($ApkFile.Name)"

if ($DryRun) {
    Write-Warn "DRY RUN: would create release $Tag and upload $($ApkFile.Name)"
} else {
    # Delete any existing release for this tag so re-running after a failed
    # attempt is safe rather than an error.
    #
    # Two subtleties, both learned the hard way:
    #
    #  * `gh release delete` writes "release not found" to STDERR when there is
    #    nothing to delete. With $ErrorActionPreference = "Stop", PowerShell
    #    promotes any native stderr output to a terminating error - so the
    #    normal case (first publish of a tag) would abort the script. The
    #    preference is therefore relaxed around this one call and $LASTEXITCODE
    #    reset, because "nothing to delete" is a success for our purposes.
    #
    #  * `--cleanup-tag` is deliberately NOT used. It would delete the git tag
    #    as well, and tags here are created and pushed by the release process
    #    before this script runs. Removing one would silently detach the release
    #    from the commit it documents.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    gh release delete $Tag --yes 2>&1 | Out-Null
    $ErrorActionPreference = $previousPreference
    $global:LASTEXITCODE = 0

    gh release create $Tag $ApkPath `
        --title "$GradleVersionName" `
        --notes $ReleaseNotes `
        --target $Branch
    if ($LASTEXITCODE -ne 0) {
        throw "gh release create failed."
    }
    Write-Ok "Release $Tag published"
}

# =============================================================================
#  6. Write the manifest the devices poll
# =============================================================================
Write-Step "Writing ota/update-manifest.json"

$otaDir = Join-Path $RepoRoot "ota"
if (-not (Test-Path $otaDir)) {
    New-Item -ItemType Directory -Path $otaDir | Out-Null
}

# Key order here matches UpdateInfo.java so the two stay readable side by side.
$manifest = [ordered]@{
    versionCode  = $VersionCode
    versionName  = $GradleVersionName
    apkUrl       = $ApkUrl
    sha256       = $Sha256
    sizeBytes    = $ApkFile.Length
    minSdk       = 24
    mandatory    = [bool]$Mandatory
    releaseNotes = $ReleaseNotes
    publishedAt  = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}

# Carry the release being replaced forward as this one's rollback target.
if ($null -ne $previousRelease) {
    $manifest["previous"] = $previousRelease
    Write-Ok "Rollback target: $($previousRelease.versionName) (versionCode $($previousRelease.versionCode))"
} else {
    Write-Warn "No previous release, so this version has no rollback target."
}

# Depth 5 comfortably covers the one level of nesting `previous` introduces.
$json = $manifest | ConvertTo-Json -Depth 5
# UTF-8 without BOM: a BOM makes the leading '{' unparseable to org.json on the
# device, and the resulting error message points nowhere useful.
[System.IO.File]::WriteAllText($ManifestPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host $json -ForegroundColor DarkGray

# =============================================================================
#  7. Commit and push the manifest
# =============================================================================
# Publishing the release is not what triggers the rollout - committing THIS file
# is. Until the manifest is on the branch the devices poll, the release is
# invisible to them.
Write-Step "Committing the manifest to '$Branch'"

if ($DryRun) {
    Write-Warn "DRY RUN: would commit and push ota/update-manifest.json to $Branch"
} else {
    <#
        Git writes ordinary progress to STDERR - "Already on 'master'",
        "Switched to branch", the push summary. With
        $ErrorActionPreference = "Stop", PowerShell promotes ANY native stderr
        output to a terminating error, so a perfectly successful checkout would
        abort the script.

        The preference is therefore relaxed for this block and success is judged
        the correct way: by $LASTEXITCODE.
    #>
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        $currentBranch = (git rev-parse --abbrev-ref HEAD 2>&1).Trim()

        if ($currentBranch -ne $Branch) {
            git checkout $Branch 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Could not switch to '$Branch'. Commit or stash your changes first."
            }
        }

        git add $ManifestPath 2>&1 | Out-Null
        git commit -m "release: publish version $GradleVersionName (versionCode $VersionCode)" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            # Nothing to commit means the manifest is byte-identical to what is
            # already on the branch. Not an error, but worth saying out loud.
            Write-Warn "Manifest unchanged; nothing to commit."
        }

        git push origin $Branch 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not push the manifest. The release exists but NO DEVICE WILL SEE IT until this file reaches '$Branch'."
        }

        if ($currentBranch -ne $Branch) {
            git checkout $currentBranch 2>&1 | Out-Null
        }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    Write-Ok "Manifest pushed. Devices will pick it up on their next check."
}

Write-Host ""
Write-Host "Done. Version $GradleVersionName (code $VersionCode) is published." -ForegroundColor Green
Write-Host "Devices check every few hours; to test immediately, call OtaUpdater.checkNow(context)." -ForegroundColor DarkGray
Write-Host ""
Write-Host "NOTE: raw.githubusercontent caches for ~5 minutes. A device checking" -ForegroundColor DarkGray
Write-Host "      in the next few minutes may still see the previous manifest." -ForegroundColor DarkGray
