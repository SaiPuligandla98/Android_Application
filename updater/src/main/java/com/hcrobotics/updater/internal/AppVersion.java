package com.hcrobotics.updater.internal;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

/**
 * Reads the version of the application currently installed on this device.
 *
 * <h2>Why this is its own class</h2>
 * Retrieving a version code is three lines of code and one platform trap. The
 * trap: {@code PackageInfo.versionCode} is an {@code int} and was deprecated in
 * Android 9 (API 28) in favour of {@code getLongVersionCode()}, which returns a
 * {@code long} combining {@code versionCodeMajor} and {@code versionCode}.
 *
 * <p>Isolating that here means the version comparison - the single decision the
 * whole module turns on - is made in one place, correctly, on every API level.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class AppVersion {

    /** Utility class; never instantiated. */
    private AppVersion() {
        throw new AssertionError("AppVersion is a utility class.");
    }

    /**
     * Returns the version code of the installed application.
     *
     * @param context any context
     * @return the installed version code, or {@code 0} if it cannot be read
     */
    public static long installedVersionCode(@NonNull Context context) {
        try {
            final PackageInfo info = packageInfo(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            // Deprecated on API 28+, but the only option below it.
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // Cannot happen: we are asking about our own package.
            UpdaterLog.e("Could not read the installed version code", e);
            return 0L;
        }
    }

    /**
     * Returns the version name of the installed application.
     *
     * @param context any context
     * @return the installed version name, or {@code "unknown"} if unreadable
     */
    @NonNull
    public static String installedVersionName(@NonNull Context context) {
        try {
            final String name = packageInfo(context).versionName;
            return name != null ? name : "unknown";
        } catch (PackageManager.NameNotFoundException e) {
            UpdaterLog.e("Could not read the installed version name", e);
            return "unknown";
        }
    }

    /**
     * Looks up this application's own package metadata.
     *
     * @param context any context
     * @return metadata for the calling package
     * @throws PackageManager.NameNotFoundException never, in practice
     */
    @NonNull
    private static PackageInfo packageInfo(@NonNull Context context)
            throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    }
}
