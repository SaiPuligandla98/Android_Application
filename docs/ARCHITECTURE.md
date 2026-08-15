# Architecture

This document explains **why** the project is shaped the way it is. For *what
each file does*, read the header comment at the top of that file — the
documentation lives next to the code so the two cannot drift apart.

---

## 1. Guiding principles

Four rules drove every structural decision here.

### Each file has one job, and says so

Every source and resource file opens with a header comment answering: what is
this, why does it exist, and what should a reader know before changing it.

Comments explain **why**, not **what**. `binding = null` is obvious; *"so the
inflated view hierarchy can be garbage collected"* is not, and that is the part
worth writing down.

### The resource system does the branching

Android's qualifier system picks resources by device configuration before any of
our code runs. Dark mode is `values-night/`. A tablet layout would be
`layout-sw600dp/`. Hindi is `values-hi/`.

The payoff: **no `if (isDarkMode)` anywhere in the codebase.** No runtime branch
means no untested code path and no chance of the two branches diverging.

### Separate what a thing *is* from what it *does*

Applied to colour, this is a two-layer system:

| Layer | File | Names describe | Example |
| ----- | ---- | -------------- | ------- |
| Palette | `values/colors.xml` | the colour itself | `brand_blue = #07518D` |
| Semantic | `values/themes.xml` | its role in the UI | `colorPrimary = @color/brand_blue` |

Layouts reference the **semantic** layer (`?attr/colorPrimary`), never the
palette. That single indirection is what makes dark mode — and any future
re-brand — a change in `themes.xml` alone.

### Build the seam before you need it

`AppLogger` wraps `android.util.Log` even though it adds nothing today beyond
tag prefixing and release stripping. When the project adopts Crashlytics or
Timber, exactly one file changes. Retrofitting that seam across a hundred call
sites is the expensive version of the same work.

The same reasoning explains `BaseActivity`, `AppConfig`, and the version
catalog.

---

## 2. Package structure

```
com.hcrobotics.testapp
├── HcRoboticsApplication      Process entry point
├── core/                      Cross-cutting, UI-agnostic infrastructure
│   ├── config/                Behavioural constants
│   └── util/                  Shared utilities
└── ui/                        Everything the user sees
    ├── base/                  Behaviour shared by all screens
    ├── splash/                One package per screen
    └── main/
```

### Why one package per screen

`ui/main/` currently holds a single file. It will not stay that way — a real
screen accumulates a ViewModel, an adapter, a UI-state class. Grouping by
**feature** rather than by **type** means everything a screen needs sits
together, so a change is a diff in one folder rather than scattered across
`activities/`, `viewmodels/` and `adapters/`.

### The `core` / `ui` split

`core` must never import from `ui`. That one-way rule keeps infrastructure
independently testable and stops utility code from quietly acquiring a
dependency on a specific screen.

---

## 3. Application startup

```
User taps icon
      │
      ▼
Android creates the process
      │
      ▼
HcRoboticsApplication.onCreate()          ← global init, before any UI
      │
      ▼
Window created; windowBackground drawn    ← @drawable/bg_splash: branded
      │                                     frame is already on screen
      ▼
SplashActivity.onCreate()                 ← inflates a pixel-identical layout
      │
      │  ... 1500 ms (AppConfig.SPLASH_DISPLAY_DURATION_MS)
      ▼
SplashActivity.navigateToMainScreen()
      │
      ▼
MainActivity                              ← splash finishes, leaves back stack
```

### The no-white-flash technique

The window manager draws an Activity's `android:windowBackground` **before the
app process has finished starting** — long before `onCreate()` inflates
anything. If that background is a plain colour, the user sees a blank frame,
then branding pops in.

`Theme.HCRobotics.Splash` sets `windowBackground` to `@drawable/bg_splash`, a
layer-list that already contains the logo. The branded frame is therefore on
screen from the first rendered pixel.

`activity_splash.xml` then draws the same logo at the same size — both read
`@dimen/splash_logo_width` and `@dimen/splash_logo_height`, which is what
guarantees they match. The handover from window background to inflated layout is
pixel-identical, and invisible.

### Why the delay is posted, not slept

`Thread.sleep()` on the main thread blocks rendering and input, and risks an
"Application Not Responding" dialog. `Handler.postDelayed()` schedules the work
and leaves the thread free.

The cost of posting is that the `Runnable` outlives the Activity that scheduled
it. Two guards handle that:

* `onDestroy()` calls `removeCallbacks()` — without it, the pending Runnable
  holds a strong reference to the Activity and its entire view tree.
* A `hasNavigated` flag makes the navigation idempotent.

Both are small. Both are the difference between a screen that behaves under
rotation and rapid back-navigation and one that leaks or crashes.

---

## 4. The UI layer

### View Binding over findViewById

`buildFeatures { viewBinding true }` generates a binding class per layout:
`activity_main.xml` → `ActivityMainBinding`.

| | `findViewById` | View Binding |
| --- | --- | --- |
| Wrong ID | Crashes at runtime | Does not compile |
| Wrong type | `ClassCastException` | Does not compile |
| View absent from a layout variant | Silent `null`, later NPE | Nullable field, flagged by the IDE |
| Boilerplate | One lookup + cast per view | None |

Bindings are nulled in `onDestroy()` so the view hierarchy can be collected.

### ConstraintLayout over nested layouts

Nested `LinearLayout`s cost a measure/layout pass per level, and deep nesting
compounds into visible jank. ConstraintLayout expresses the same design as a
**flat** hierarchy by describing relationships between siblings, and adapts to
any screen size without a second XML file.

### BaseActivity — and its limits

`BaseActivity` provides lifecycle logging and one enforced log tag per screen.
It is the natural home for behaviour that is genuinely universal: window insets,
an offline banner, session-expiry checks, analytics screen tracking.

The caution is real, though. A base class is inheritance, and inheritance is
hard to opt out of once a screen needs to differ. Add something here only when
it applies to *every* screen; anything narrower belongs in a helper or delegate
that screens choose to use.

---

## 5. The build

### Version catalog

`gradle/libs.versions.toml` owns every version number. Modules reference
`libs.androidx.appcompat`, not a hard-coded coordinate string.

* One place to bump a version, so no drift between modules.
* The IDE autocompletes entries and catches typos at sync time.
* An upgrade is a one-line, reviewable diff.

### Build types

| | debug | release |
| --- | --- | --- |
| Application ID | `…testapp.debug` | `…testapp` |
| Code shrinking (R8) | off | on |
| Resource shrinking | off | on |
| Verbose logging | on | compiled out |
| Debuggable | yes | no |

The `.debug` suffix lets both builds coexist on one device — invaluable when
comparing behaviour, and the reason `adb` commands must name the right package.

### Repository lockdown

`settings.gradle` declares repositories centrally and sets
`FAIL_ON_PROJECT_REPOS`. No module can quietly introduce an untrusted artifact
source. Standard supply-chain hygiene.

---

## 6. Extending the app

### Adding a screen

1. Create `ui/<feature>/` and a `<Feature>Activity` extending `BaseActivity`.
2. Create `res/layout/activity_<feature>.xml`.
3. Declare the Activity in `AndroidManifest.xml` with `android:exported="false"`
   unless something outside the app genuinely needs to start it.
4. Add its strings to `res/values/strings.xml`.

### When to introduce a ViewModel

As soon as a screen holds state that must survive rotation, or performs work
that outlives a single frame. An Activity is destroyed and recreated on every
configuration change; a `ViewModel` is not. Keeping state there means rotation
is free rather than a bug to be fixed.

### When to introduce a repository layer

As soon as data comes from more than one place (network *and* cache, say). A
repository decides *where* data comes from; the ViewModel decides *what to do
with it*. Keeping that boundary sharp is what makes both testable.

The `core/` package is where those pieces belong — `core/data/`,
`core/network/` — since they are UI-agnostic by definition.
