# Git workflow

How this repository is branched, committed and released — and *why* each rule
exists. Written to be followed, not admired.

---

## 1. The branch model

This project uses **Git Flow**, the model most enterprise Android teams run.

```
master   ●────────────────────────●────────────────────────●
         │  v1.0.0                │  v1.1.0                │  v1.2.0
         │  (production)          │                        │
         │                        │                        │
dev      ●────●────●────●─────────●────●────●──────────────●
         │    ▲    ▲    ▲              ▲    ▲
         │    │    │    │              │    │
feature  │    ●────┘    │              ●────┘
         │   ota-update │             fix-crash
         │              │
         └──────────────●
                    splash-screen
```

| Branch | Purpose | Rule |
|---|---|---|
| `master` | **Production.** Exactly what is running on the devices. | Never commit directly. Only merges from `dev`, always tagged. |
| `dev` | **Integration.** Where finished features land and are tested together. | Never commit directly. Only merges from `feature/*`. |
| `feature/*` | **One branch per piece of work.** | Cut from `dev`. Merged back to `dev` via a Pull Request. |

### Why `master` is not where you work

`AppConfig.UPDATE_MANIFEST_URL` points at the **`master`** branch. Every device
in the fleet polls that file. A stray commit on `master` is not an untidy
history — it is a release to 50 devices.

That single fact is what makes the discipline worth it here.

---

## 2. Daily workflow

### Starting a feature

```bash
git checkout dev
git pull origin dev                       # start from what everyone else has
git checkout -b feature/notification-badge
```

**Always branch from `dev`, never from `master`.** Branching from `master` means
your feature is missing everything merged since the last release, and you
discover it as conflicts at merge time.

### While working

Commit in small, logical steps. A commit should be one idea:

```bash
git add updater/src/main/java/com/hcrobotics/updater/OtaConfig.java
git commit -m "feat(updater): add unmetered-network constraint option"
```

Small commits are not bureaucracy — they are what makes `git revert`,
`git bisect` and code review actually usable. A 40-file commit called
"changes" can only be reverted wholesale.

### Finishing a feature

```bash
git push -u origin feature/notification-badge
gh pr create --base dev --head feature/notification-badge \
    --title "feat(updater): notification badge for pending updates"
```

The PR targets **`dev`**, never `master`.

---

## 3. Commit messages: Conventional Commits

```
<type>(<scope>): <subject>

<body — WHY, not what. The diff already shows what.>
```

| Type | Use for |
|---|---|
| `feat` | A new capability |
| `fix` | A bug fix |
| `docs` | Documentation only |
| `refactor` | Restructuring with no behaviour change |
| `perf` | A performance improvement |
| `test` | Adding or fixing tests |
| `build` | Gradle, dependencies, tooling |
| `chore` | Housekeeping |
| `release` | A version publish |

**Good:**

```
fix(updater): follow redirects manually when downloading the APK

HttpURLConnection refuses to auto-follow redirects that change host, and
every GitHub release download redirects to objects.githubusercontent.com.
The download returned zero bytes and failed its checksum with no clue why.
```

**Bad:** `fixed bug`, `update`, `asdf`, `final version 2 FINAL`.

The test: in six months, will the message explain the change to someone who
has never seen this code? Including you.

---

## 4. Releasing

A release is a merge from `dev` to `master`, plus a tag, plus a published APK.

```bash
# 1. Bump the version. versionCode MUST increase or no device will update.
#    Edit app/build.gradle: versionCode 2, versionName "1.1.0"
git checkout dev
git add app/build.gradle
git commit -m "build: bump version to 1.1.0 (versionCode 2)"
git push origin dev

# 2. Merge dev into master via a Pull Request (reviewed, not local).
gh pr create --base master --head dev --title "release: v1.1.0"

# 3. After the PR is merged, tag the release point.
git checkout master
git pull origin master
git tag -a v1.1.0 -m "Release 1.1.0"
git push origin v1.1.0

# 4. Build, upload and publish the manifest — this is the actual rollout.
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\tools\publish-update.ps1 -VersionName "1.1.0" -ReleaseNotes "Faster startup"
```

### Why tags matter here

A tag is a permanent, immutable name for one commit. When a device in the field
reports a bug on version 1.1.0, `git checkout v1.1.0` gives you **the exact
source that produced the APK on that device**. Without tags you are guessing
from dates.

### Hotfixes

A production-only emergency branches from `master`, not `dev`:

```bash
git checkout master
git checkout -b hotfix/crash-on-launch
# ... fix, bump versionCode ...
gh pr create --base master --head hotfix/crash-on-launch
# then merge master back into dev so the fix is not lost next release
git checkout dev && git merge master && git push origin dev
```

That last step is the one people forget. Skip it and the next release from `dev`
silently reintroduces the bug you just fixed.

---

## 5. Useful commands

```bash
git log --oneline --graph --all --decorate   # see the branch shape
git status                                   # what is staged vs not
git diff                                     # unstaged changes
git diff --staged                            # what a commit would contain
git switch -                                 # back to the previous branch
git restore <file>                           # discard local changes to a file
git reset --soft HEAD~1                      # undo last commit, keep changes
git revert <sha>                             # undo a PUSHED commit safely
```

**`revert`, not `reset`, for anything already pushed.** `reset` rewrites
history; anyone who pulled the old commits gets conflicts. `revert` adds a new
commit that undoes the old one, which is honest and safe.

---

## 6. What is never committed

Enforced by `.gitignore`:

| Excluded | Why |
|---|---|
| `build/`, `.gradle/` | Regenerated. Committing causes conflicts on every build. |
| `local.properties` | Machine-specific SDK path. |
| `*.jks`, `keystore.properties` | **Signing keys.** In a public repo, anyone could sign an APK the fleet accepts as genuine. |
| `.idea/`, `*.iml` | Per-developer IDE state. |

The keystore is the serious one. Its absence from the repository is what keeps
the update channel trustworthy.

---

## 7. This repository's setup

Identity is configured **locally**, so nothing on the machine is affected:

```bash
git config user.name  "SaiPuligandla98"          # note: no --global
git config user.email "saipuligandla05@gmail.com"
```

`git config --global` would change every repository on the computer. `--local`
(the default) writes only to `.git/config` in this project.

### Line endings

`.gitattributes` normalises everything to LF in the repository while letting
Windows check out CRLF. Without it, the same file edited on Windows and Linux
shows as *every line changed*, and code review becomes impossible.
