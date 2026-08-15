package com.hcrobotics.appinsights.internal;

import androidx.annotation.NonNull;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Formats sizes and dates for the inventory.
 *
 * <h2>Why the module carries its own copy</h2>
 * The performance module has a similar class. Sharing one would mean a third
 * "common" module that both depend on — extra structure to carry two small
 * methods, and a dependency edge between features that are otherwise entirely
 * independent.
 *
 * <p>Two short, well-tested duplicates are cheaper here than the coupling that
 * removing them would introduce. That trade would flip if a third module needed
 * the same code.</p>
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class Units {

    /** Decimal units, matching what Android Settings and app stores display. */
    private static final long KB = 1000L;
    private static final long MB = KB * 1000L;
    private static final long GB = MB * 1000L;

    /** Utility class; never instantiated. */
    private Units() {
        throw new AssertionError("Units is a utility class.");
    }

    /**
     * Formats a byte count, choosing the unit that keeps the number readable.
     *
     * @param bytes a byte count
     * @return e.g. {@code "142 MB"}, {@code "1.24 GB"}
     */
    @NonNull
    public static String bytes(long bytes) {
        if (bytes <= 0) {
            return "-";
        }
        if (bytes >= GB) {
            return String.format(Locale.US, "%.2f GB", bytes / (double) GB);
        }
        if (bytes >= MB) {
            return String.format(Locale.US, "%.1f MB", bytes / (double) MB);
        }
        return String.format(Locale.US, "%.0f KB", bytes / (double) KB);
    }

    /**
     * Formats an epoch timestamp as a local date.
     *
     * <p>Uses the device's own locale and format rather than a fixed pattern,
     * so a date reads the way the user expects it to — 15/08/2026 or 8/15/2026
     * depending on where they are.</p>
     *
     * @param epochMillis milliseconds since the epoch
     * @return a localised date, or {@code "-"} if the timestamp is unset
     */
    @NonNull
    public static String date(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(epochMillis));
    }

    /**
     * Maps an API level to the Android version people recognise.
     *
     * <p>"targetSdk 34" means nothing to most readers; "Android 14 (API 34)"
     * does. Only levels this app can encounter are listed — its own minimum is
     * API 24 — and anything newer than the table falls back to the raw number
     * rather than guessing at a name.</p>
     *
     * @param apiLevel an Android API level
     * @return e.g. {@code "Android 14 (API 34)"}
     */
    @NonNull
    public static String androidVersion(int apiLevel) {
        final String name;
        switch (apiLevel) {
            case 21: case 22: name = "5"; break;
            case 23: name = "6"; break;
            case 24: case 25: name = "7"; break;
            case 26: case 27: name = "8"; break;
            case 28: name = "9"; break;
            case 29: name = "10"; break;
            case 30: name = "11"; break;
            case 31: case 32: name = "12"; break;
            case 33: name = "13"; break;
            case 34: name = "14"; break;
            case 35: name = "15"; break;
            case 36: name = "16"; break;
            default: return "API " + apiLevel;
        }
        return "Android " + name + " (API " + apiLevel + ")";
    }
}
