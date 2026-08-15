#
# =============================================================================
#  consumer-rules.pro  --  R8 rules that travel WITH this library
# =============================================================================
#
#  HOW THIS DIFFERS FROM proguard-rules.pro
#  ----------------------------------------
#  A library is never shrunk on its own. It is shrunk as part of whichever
#  application consumes it, by that application's R8 run - which knows nothing
#  about this library's internals.
#
#  Rules in THIS file are packaged into the AAR and automatically applied to
#  that consuming application's R8 run. The host app therefore needs no manual
#  configuration: it depends on the module and the correct keep rules come along
#  for free. That is a core part of what makes this module plug-and-play.
#
#  WHAT NEEDS KEEPING, AND WHY
#  ---------------------------
#  Anything the ANDROID FRAMEWORK instantiates by name rather than our code
#  constructing directly. R8 cannot see those references, so without a rule it
#  would delete or rename the class and the app would crash at runtime.
# =============================================================================

# --- WorkManager Workers -----------------------------------------------------
# WorkManager persists the worker's fully-qualified class NAME in its database
# and reflectively instantiates it later - possibly days later, after a reboot.
# If R8 renames the class, that lookup fails and the update check silently stops.
-keep class com.hcrobotics.updater.work.** { *; }

# --- Manifest-declared components --------------------------------------------
# Activities and BroadcastReceivers are named in AndroidManifest.xml and
# instantiated by the system.
-keep class com.hcrobotics.updater.ui.UpdateActivity { *; }
-keep class com.hcrobotics.updater.internal.InstallResultReceiver { *; }

# --- Public API --------------------------------------------------------------
# The surface host applications call. Keeping it makes stack traces from the
# field readable and guarantees the entry points survive shrinking.
-keep public class com.hcrobotics.updater.OtaUpdater { public *; }
-keep public class com.hcrobotics.updater.OtaConfig { public *; }
-keep public class com.hcrobotics.updater.OtaConfig$Builder { public *; }
-keep public class com.hcrobotics.updater.UpdateInfo { public *; }

# --- Readable crash reports --------------------------------------------------
-keepattributes SourceFile,LineNumberTable
