#
# =============================================================================
#  proguard-rules.pro  --  R8 / ProGuard keep rules for the release build
# =============================================================================
#
#  R8 shrinks, optimises and obfuscates the release APK. It removes anything it
#  cannot prove is reachable. Anything reached ONLY through reflection, XML, or
#  the framework itself must therefore be protected with an explicit rule.
#
#  This project is intentionally small and needs almost nothing kept. The rules
#  below are the ones that pay for themselves on every real project.
# =============================================================================

# --- Crash-report readability ------------------------------------------------
# Preserve line numbers so release stack traces can be de-obfuscated with the
# mapping file emitted at app/build/outputs/mapping/release/mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Views inflated from XML -------------------------------------------------
# Custom views are instantiated reflectively by the LayoutInflater using the
# (Context, AttributeSet) constructor. Keep it.
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# --- android:onClick="methodName" --------------------------------------------
# Resolved reflectively at click time; the method name must survive.
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# --- Application entry points ------------------------------------------------
# Activities and the Application class are named in AndroidManifest.xml and
# instantiated by the system, so their names must not be renamed.
-keep public class com.hcrobotics.testapp.HcRoboticsApplication
-keep public class * extends android.app.Activity
