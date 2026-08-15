#
# =============================================================================
#  consumer-rules.pro  --  R8 rules packaged with this library
# =============================================================================
#
#  Applied automatically to whichever application consumes this module, so the
#  host app needs no manual configuration.
#
#  Only the Activity needs keeping: it is named in AndroidManifest.xml and
#  instantiated by the system, so R8 cannot see the reference and would
#  otherwise be free to rename or remove it.
#
#  The collectors are all called from ordinary Java, so R8 can trace them and
#  they need no rules of their own.
# =============================================================================

-keep class com.hcrobotics.performance.PerformanceActivity { *; }

# Keep line numbers so field crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
