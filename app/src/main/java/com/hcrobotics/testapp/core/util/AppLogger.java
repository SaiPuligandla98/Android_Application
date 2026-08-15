package com.hcrobotics.testapp.core.util;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hcrobotics.testapp.BuildConfig;

/**
 * Centralised logging facade for the entire application.
 *
 * <h2>Why not call {@link android.util.Log} directly?</h2>
 * <ul>
 *   <li><b>Leak prevention.</b> Verbose and debug logs are compiled to run only
 *       in debug builds. Release builds therefore cannot accidentally print
 *       internal state, device identifiers, or API payloads to logcat, where
 *       any other app with READ_LOGS-equivalent access could observe them.</li>
 *   <li><b>Consistency.</b> Every tag is automatically prefixed with
 *       {@value #TAG_PREFIX}, so filtering logcat by that one string shows
 *       everything this app emitted and nothing else.</li>
 *   <li><b>A single seam.</b> When the project later adopts Timber, Firebase
 *       Crashlytics or a file-based log sink, only this class changes — no call
 *       site has to be touched.</li>
 * </ul>
 *
 * <h2>Which level should I use?</h2>
 * <table border="1">
 *   <caption>Log level selection guide</caption>
 *   <tr><th>Level</th><th>Use for</th><th>Present in release?</th></tr>
 *   <tr><td>{@link #d}</td><td>Fine-grained developer tracing</td><td>No</td></tr>
 *   <tr><td>{@link #i}</td><td>Notable lifecycle / state milestones</td><td>No</td></tr>
 *   <tr><td>{@link #w}</td><td>Recoverable anomalies worth noticing</td><td>Yes</td></tr>
 *   <tr><td>{@link #e}</td><td>Failures that broke a user-visible operation</td><td>Yes</td></tr>
 * </table>
 *
 * <p>This class is stateless, thread-safe and cannot be instantiated.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class AppLogger {

    /**
     * Prefix prepended to every tag.
     *
     * <p>Filter logcat with {@code adb logcat -s} or the string {@code HCR/} to
     * isolate this application's output.</p>
     */
    private static final String TAG_PREFIX = "HCR/";

    /**
     * Android truncates log tags longer than 23 characters on older platform
     * versions, so composed tags are clipped defensively.
     */
    private static final int MAX_TAG_LENGTH = 23;

    /** {@code true} only in debug builds; constant-folded away by R8 in release. */
    private static final boolean VERBOSE_LOGGING_ENABLED = BuildConfig.DEBUG;

    /** Utility class — never instantiated. */
    private AppLogger() {
        throw new AssertionError("AppLogger is a utility class and must not be instantiated.");
    }

    /**
     * Logs a debug message. Stripped from release builds.
     *
     * @param tag     source of the message, typically the simple class name
     * @param message human-readable description of what happened
     */
    public static void d(@NonNull String tag, @NonNull String message) {
        if (VERBOSE_LOGGING_ENABLED) {
            Log.d(buildTag(tag), message);
        }
    }

    /**
     * Logs an informational milestone. Stripped from release builds.
     *
     * @param tag     source of the message, typically the simple class name
     * @param message human-readable description of what happened
     */
    public static void i(@NonNull String tag, @NonNull String message) {
        if (VERBOSE_LOGGING_ENABLED) {
            Log.i(buildTag(tag), message);
        }
    }

    /**
     * Logs a recoverable anomaly. Retained in release builds.
     *
     * @param tag     source of the message, typically the simple class name
     * @param message human-readable description of what happened
     */
    public static void w(@NonNull String tag, @NonNull String message) {
        Log.w(buildTag(tag), message);
    }

    /**
     * Logs a failure. Retained in release builds.
     *
     * @param tag       source of the message, typically the simple class name
     * @param message   human-readable description of what failed
     * @param throwable the causing exception, or {@code null} if there is none
     */
    public static void e(@NonNull String tag, @NonNull String message, @Nullable Throwable throwable) {
        if (throwable != null) {
            Log.e(buildTag(tag), message, throwable);
        } else {
            Log.e(buildTag(tag), message);
        }
    }

    /**
     * Combines {@link #TAG_PREFIX} with the caller's tag and clips the result to
     * {@link #MAX_TAG_LENGTH} so it is never silently truncated by the platform.
     *
     * @param tag the caller-supplied tag
     * @return a prefixed, length-safe log tag
     */
    @NonNull
    private static String buildTag(@NonNull String tag) {
        final String composed = TAG_PREFIX + tag;
        return composed.length() <= MAX_TAG_LENGTH
                ? composed
                : composed.substring(0, MAX_TAG_LENGTH);
    }
}
