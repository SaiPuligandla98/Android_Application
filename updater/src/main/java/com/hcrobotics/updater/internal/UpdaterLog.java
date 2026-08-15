package com.hcrobotics.updater.internal;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hcrobotics.updater.BuildConfig;

/**
 * Internal logging facade for the updater module.
 *
 * <h2>Why the module does not use the host app's logger</h2>
 * This library is designed to be dropped into any Android project. It therefore
 * cannot assume the host app has a particular logging class, or any logging
 * abstraction at all. Keeping a tiny private logger inside the module preserves
 * that independence.
 *
 * <h2>Consistent tagging</h2>
 * Every line is tagged {@code HCOta}, so a single logcat filter shows the
 * complete update lifecycle across every app that embeds this module:
 *
 * <pre>adb logcat -s HCOta</pre>
 *
 * <h2>Verbosity</h2>
 * Debug and info logging follows {@link #setDebugLoggingEnabled(boolean)},
 * which {@code OtaConfig} sets during initialisation. Warnings and errors are
 * always emitted, because a silent failure in a background update check is
 * almost impossible to diagnose from the field.
 *
 * <p>Package-private to the module by convention: it lives in {@code internal}
 * and is not part of the public API.</p>
 */
public final class UpdaterLog {

    /** Shared tag for every line this module emits. */
    private static final String TAG = "HCOta";

    /**
     * Whether debug/info logging is emitted.
     *
     * <p>Defaults to the module's own build type and is overridden by
     * {@code OtaConfig.Builder.debugLogging(boolean)}. Marked {@code volatile}
     * because it is written on the main thread during initialisation and read
     * from WorkManager's background threads.</p>
     */
    private static volatile boolean debugLoggingEnabled = BuildConfig.DEBUG;

    /** Utility class; never instantiated. */
    private UpdaterLog() {
        throw new AssertionError("UpdaterLog is a utility class.");
    }

    /**
     * Enables or disables debug and info output for the whole module.
     *
     * @param enabled {@code true} to emit debug/info lines
     */
    public static void setDebugLoggingEnabled(boolean enabled) {
        debugLoggingEnabled = enabled;
    }

    /**
     * Logs a debug message, if debug logging is enabled.
     *
     * @param message text to record
     */
    public static void d(@NonNull String message) {
        if (debugLoggingEnabled) {
            Log.d(TAG, message);
        }
    }

    /**
     * Logs an informational milestone, if debug logging is enabled.
     *
     * @param message text to record
     */
    public static void i(@NonNull String message) {
        if (debugLoggingEnabled) {
            Log.i(TAG, message);
        }
    }

    /**
     * Logs a recoverable anomaly. Always emitted.
     *
     * @param message text to record
     */
    public static void w(@NonNull String message) {
        Log.w(TAG, message);
    }

    /**
     * Logs a failure. Always emitted.
     *
     * @param message   what failed
     * @param throwable the cause, or {@code null} if there is none
     */
    public static void e(@NonNull String message, @Nullable Throwable throwable) {
        if (throwable != null) {
            Log.e(TAG, message, throwable);
        } else {
            Log.e(TAG, message);
        }
    }
}
