#
# =============================================================================
#  consumer-rules.pro  --  R8 rules packaged with this library
# =============================================================================
#
#  Applied automatically to whichever application consumes this module.
#
#  Only the Activity needs keeping: it is named in AndroidManifest.xml and
#  instantiated by the system, so R8 cannot see the reference and would be free
#  to rename or remove them.
#
#  The scanner and adapter are reached from ordinary Java, so R8 can trace them
#  and they need no rules.
# =============================================================================

-keep class com.hcrobotics.appinsights.AppInsightsActivity { *; }

-keepattributes SourceFile,LineNumberTable
