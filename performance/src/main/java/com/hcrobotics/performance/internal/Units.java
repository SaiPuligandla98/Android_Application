package com.hcrobotics.performance.internal;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Formats raw numbers into something a person can read at a glance.
 *
 * <h2>Why this is not scattered through the collectors</h2>
 * Every collector produces byte counts, durations or percentages. If each
 * formatted its own, the screen would show "1.5 GB" beside "1536 MB" beside
 * "1610612736" — all correct, none comparable. One formatter keeps the whole
 * report internally consistent.
 *
 * <h2>A note on GB vs GiB</h2>
 * Storage is reported in decimal units (1 GB = 1000 MB) because that is what
 * device manufacturers print on the box and what Android's own Settings screen
 * shows. Reporting binary units would be more technically precise and would
 * make a "64 GB" phone appear to have 59 GB, which reads as a fault rather than
 * a convention.
 *
 * <p>Memory uses the same scale for consistency within this screen.</p>
 *
 * @author HC Robotics
 * @since 1.8.0
 */
public final class Units {

    /** Decimal, to match what Android Settings and device packaging report. */
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
     * @param bytes a non-negative byte count
     * @return e.g. {@code "3.4 GB"}, {@code "812 MB"}, {@code "64 KB"}
     */
    @NonNull
    public static String bytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes >= GB) {
            return String.format(Locale.US, "%.2f GB", bytes / (double) GB);
        }
        if (bytes >= MB) {
            return String.format(Locale.US, "%.0f MB", bytes / (double) MB);
        }
        if (bytes >= KB) {
            return String.format(Locale.US, "%.0f KB", bytes / (double) KB);
        }
        return bytes + " B";
    }

    /**
     * Renders "used of total" with the proportion, the shape most of this
     * screen's readings take.
     *
     * @param used  bytes in use
     * @param total bytes available in total
     * @return e.g. {@code "3.40 GB of 7.86 GB (43%)"}
     */
    @NonNull
    public static String usedOfTotal(long used, long total) {
        return bytes(used) + " of " + bytes(total) + " (" + percent(used, total) + "%)";
    }

    /**
     * Computes a whole-number percentage, guarding against division by zero.
     *
     * <p>A zero total is not hypothetical: a storage volume can report zero
     * while it is being mounted, and an uncaught divide there would crash the
     * diagnostics screen precisely when someone is using it to diagnose.</p>
     *
     * @param part  the portion
     * @param total the whole
     * @return 0-100, or 0 when {@code total} is not positive
     */
    public static int percent(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.round((part * 100.0) / total);
    }

    /**
     * Formats a duration in milliseconds as days, hours and minutes.
     *
     * <p>Only the units that are non-zero appear, so a freshly booted device
     * reads "12 minutes" rather than "0 days 0 hours 12 minutes".</p>
     *
     * @param millis elapsed milliseconds
     * @return e.g. {@code "2 days 4 hours"}, {@code "37 minutes"}
     */
    @NonNull
    public static String duration(long millis) {
        final long days = TimeUnit.MILLISECONDS.toDays(millis);
        final long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;

        final StringBuilder text = new StringBuilder();
        if (days > 0) {
            text.append(days).append(days == 1 ? " day " : " days ");
        }
        if (hours > 0) {
            text.append(hours).append(hours == 1 ? " hour " : " hours ");
        }
        if (days == 0 && (minutes > 0 || text.length() == 0)) {
            text.append(minutes).append(minutes == 1 ? " minute" : " minutes");
        }
        return text.toString().trim();
    }
}
